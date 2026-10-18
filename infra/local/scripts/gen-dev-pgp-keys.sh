#!/usr/bin/env bash
# 開発用の PGP の鍵を GnuPG で infra/local/secrets/ に作る(P09 ⑥。ADR-0032 §8)。`make pgp-keys` と `make up`(file の profile)から呼ぶ。
# - eiaf: 自社の鍵(file-exchange が送るファイルに署名する)。partner01: 取引先の代わりの鍵(file-exchange が暗号化に使い、
#   取引先の側として make verify が復号して署名を確かめる)。
# - 鍵は GnuPG(infra/local/images/gnupg)で作る。取引先が実際に使う gpg の鍵を、file-exchange(Bouncy Castle)が読めることを
#   確かめるため(GnuPG との相互運用。承認の条件)。主鍵は署名と証明、副鍵は暗号化(RSA 3072)。秘密鍵はパスフレーズで保護する。
# - 既にあれば何もしない(作り直すときは secrets/pgp-* を消してから実行する。docs/runbooks/file-exchange.md)。
# - secrets/ は .gitignore 済み。鍵とパスフレーズはコミットしない。
#
# 作るファイル(<name> は eiaf と partner01):
#   pgp-<name>.pub.asc     公開鍵(ASCII armor)
#   pgp-<name>.sec.asc     秘密鍵(ASCII armor。パスフレーズで保護)
#   pgp-<name>.passphrase  パスフレーズ(改行なし)
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
dir="$here/secrets"
image=eiaf-gnupg:local
names=(eiaf partner01)
user_id() { # macOS の bash 3.2 には連想配列がない
  case "$1" in
    eiaf) echo "EIAF Dev <eiaf-dev@example.invalid>" ;;
    partner01) echo "Partner01 Dev <partner01-dev@example.invalid>" ;;
  esac
}

if [[ "$(id -u)" == 0 ]]; then
  echo "root(uid 0)で実行しないでください。file-exchange はホストの利用者の uid で動き、この利用者が作った 0600 の鍵を読みます" >&2
  exit 2
fi

missing=()
for name in "${names[@]}"; do
  for suffix in pub.asc sec.asc passphrase; do
    [[ -s "$dir/pgp-$name.$suffix" ]] || { missing+=("$name"); break; }
  done
done
[[ ${#missing[@]} -eq 0 ]] && exit 0

# shellcheck disable=SC1091
set -a; . "$here/images.env"; set +a
docker build -q -t "$image" --build-arg DEBIAN_IMAGE="$DEBIAN_IMAGE" "$here/images/gnupg" >/dev/null

umask 077
mkdir -p "$dir"
for name in "${missing[@]}"; do
  rm -f "$dir/pgp-$name".{pub.asc,sec.asc,passphrase}
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32 >"$dir/pgp-$name.passphrase" || true
  # 鍵束はコンテナの中の一時ディレクトリ(コンテナを消せば消える)。パスフレーズは標準入力で渡す(コマンドの引数に出さない)
  docker run --rm -i --user "$(id -u):$(id -g)" --entrypoint bash -e UID_TEXT="$(user_id "$name")" "$image" -c '
    set -euo pipefail
    mkdir -m 700 -p "$GNUPGHOME"
    read -r passphrase || true # パスフレーズのファイルは改行で終わらない
    gpg() { command gpg --batch --quiet --no-auto-check-trustdb --pinentry-mode loopback --passphrase-fd 3 "$@" 3<<<"$passphrase"; }
    gpg --quick-generate-key "$UID_TEXT" rsa3072 sign,cert 2y
    fpr="$(command gpg --batch --with-colons --list-secret-keys "$UID_TEXT" | awk -F: "/^fpr:/ { print \$10; exit }")"
    gpg --quick-add-key "$fpr" rsa3072 encr 2y
    command gpg --batch --armor --export "$fpr" >/tmp/pub.asc
    gpg --armor --export-secret-keys "$fpr" >/tmp/sec.asc
    printf "%s\n" "-----PUB-----"; cat /tmp/pub.asc; printf "%s\n" "-----SEC-----"; cat /tmp/sec.asc
  ' <"$dir/pgp-$name.passphrase" >"$dir/pgp-$name.out"
  awk '/^-----PUB-----$/ { f = "pub"; next } /^-----SEC-----$/ { f = "sec"; next } { print > (dir "/pgp-" name "." f ".asc") }' \
    dir="$dir" name="$name" "$dir/pgp-$name.out"
  rm -f "$dir/pgp-$name.out"
  grep -q 'BEGIN PGP PUBLIC KEY BLOCK' "$dir/pgp-$name.pub.asc" && grep -q 'BEGIN PGP PRIVATE KEY BLOCK' "$dir/pgp-$name.sec.asc" ||
    { echo "pgp-$name の鍵を作れませんでした" >&2; exit 1; }
  chmod 644 "$dir/pgp-$name.pub.asc"
  echo "infra/local/secrets/pgp-$name.{pub.asc,sec.asc,passphrase} を GnuPG で作りました"
done

#!/usr/bin/env bash
# 開発用の CA と mTLS の証明書を infra/local/certs/ に作る(ADR-0008 / ADR-0024 §6)。`make certs` と `make up` から呼ぶ。
# - certs/ は .gitignore 済み。鍵はコミットしない。
# - 既にあれば、有効期限と署名を確かめる。残りが RENEW_DAYS(7 日)を切っている・CA で検証できない・ない証明書だけを作り直す。
#   CA を作り直したら、すべての証明書を作り直す。
# - 鍵は EC P-256 の PKCS#8(order-service は PEM から読む)。証明書の SAN は DNS 名(order-service は SAN で許可の一覧を確かめる)。
# - 作り直したら certs/.renewed を残す。起動中のコンテナは証明書を起動時にしか読まないので、make up が .renewed を見て、
#   証明書を使うコンテナを作り直す(Makefile の CERT_CONSUMERS)。
# 手順と、期限切れで起動に失敗したときの対処は docs/runbooks/dev-certificates.md。
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
dir="$here/certs"
RENEW_DAYS=7
CA_DAYS=90
LEAF_DAYS=30
# 証明書の名前と SAN の DNS 名
leaves=(
  "order-service:order-service,localhost"
  "apisix:apisix"
)

if [[ "$(id -u)" == 0 ]]; then
  echo "root(uid 0)で実行しないでください。コンテナはホストの利用者の uid で動き、この利用者が作った 0600 の鍵を読みます(ADR-0024 §7)" >&2
  exit 2
fi
command -v openssl >/dev/null || { echo "openssl が見つかりません。インストールしてから make certs を実行してください" >&2; exit 2; }

umask 077
mkdir -p "$dir"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

valid_for_renew_window() { # 残りが RENEW_DAYS 以上あれば成功
  openssl x509 -checkend $((RENEW_DAYS * 86400)) -noout -in "$1" >/dev/null 2>&1
}

not_after() { openssl x509 -enddate -noout -in "$1" | sed 's/^notAfter=//'; }

new_key() { openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$1" 2>/dev/null; chmod 600 "$1"; }

serial() { echo "0x$(openssl rand -hex 16)"; }

renewed=false

issue_ca() {
  new_key "$dir/ca.key"
  cat >"$work/ca.ext" <<'EOF'
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
EOF
  openssl req -new -key "$dir/ca.key" -subj "/CN=EIAF Local Dev CA" -out "$work/ca.csr"
  openssl x509 -req -in "$work/ca.csr" -signkey "$dir/ca.key" -days "$CA_DAYS" -sha256 \
    -set_serial "$(serial)" -extfile "$work/ca.ext" -out "$dir/ca.crt" 2>/dev/null
  chmod 644 "$dir/ca.crt"
  renewed=true
  echo "開発用の CA を作りました(notAfter=$(not_after "$dir/ca.crt"))"
}

issue_leaf() { # issue_leaf <名前> <DNS 名のカンマ区切り>
  local name="$1" sans="$2" san_list=""
  IFS=',' read -r -a names <<<"$sans"
  for n in "${names[@]}"; do san_list+="${san_list:+,}DNS:$n"; done
  new_key "$dir/$name.key"
  cat >"$work/$name.ext" <<EOF
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = serverAuth, clientAuth
subjectAltName = $san_list
authorityKeyIdentifier = keyid
EOF
  openssl req -new -key "$dir/$name.key" -subj "/CN=$name" -out "$work/$name.csr"
  openssl x509 -req -in "$work/$name.csr" -CA "$dir/ca.crt" -CAkey "$dir/ca.key" -days "$LEAF_DAYS" -sha256 \
    -set_serial "$(serial)" -extfile "$work/$name.ext" -out "$dir/$name.crt" 2>/dev/null
  chmod 644 "$dir/$name.crt"
  renewed=true
  echo "証明書 $name を作りました(SAN=${sans}、notAfter=$(not_after "$dir/$name.crt"))"
}

# CA: ない・期限が近い・期限切れなら作り直す(すべての証明書も作り直す)
renew_all=false
if [[ ! -f "$dir/ca.crt" || ! -f "$dir/ca.key" ]]; then
  renew_all=true
elif ! valid_for_renew_window "$dir/ca.crt"; then
  echo "開発用の CA の有効期限が ${RENEW_DAYS} 日以内か、切れています(notAfter=$(not_after "$dir/ca.crt"))。作り直します"
  renew_all=true
fi
$renew_all && issue_ca

for entry in "${leaves[@]}"; do
  name="${entry%%:*}" sans="${entry#*:}"
  crt="$dir/$name.crt" key="$dir/$name.key"
  if $renew_all || [[ ! -f "$crt" || ! -f "$key" ]]; then
    issue_leaf "$name" "$sans"
  elif ! openssl verify -CAfile "$dir/ca.crt" "$crt" >/dev/null 2>&1; then
    echo "証明書 $name を今の CA で検証できません。作り直します"
    issue_leaf "$name" "$sans"
  elif ! valid_for_renew_window "$crt"; then
    echo "証明書 $name の有効期限が ${RENEW_DAYS} 日以内か、切れています(notAfter=$(not_after "$crt"))。作り直します"
    issue_leaf "$name" "$sans"
  fi
done

# 鍵は 0600 のまま。コンテナはこの利用者の uid で動いて読む(Makefile の EIAF_UID。docker-compose.yml の order-service)
chmod 755 "$dir"

if $renewed; then
  touch "$dir/.renewed"
  echo "証明書を作り直しました。起動中のコンテナは古い証明書を使っています。make up で入れ替えます"
fi
echo "開発用の証明書は有効です(infra/local/certs。残りが ${RENEW_DAYS} 日を切ると make up / make certs で作り直します)"

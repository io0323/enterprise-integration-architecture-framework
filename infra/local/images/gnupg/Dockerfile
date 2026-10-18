# GnuPG のイメージ(P09 ⑥。ADR-0032 §8)。開発用の PGP の鍵を作り(make pgp-keys)、取引先の側として、送ったファイルを
# 復号して署名を確かめる(make verify PROFILE=file)。file-exchange(Bouncy Castle)と GnuPG の相互運用を確かめるために使う。
#
# ベースは images.env の DEBIAN_IMAGE(build 引数で受け取る。既定値は置かない)。パッケージは Debian の版で固定する。
# Debian のポイントリリースで古い版はリポジトリから消える(snapshot.debian.org にだけ残る)ので、定期確認(check-pins.py。#35)で
# 固定した版を今も取得できるかを確かめる。消えていたら Issue が作られるので、版を上げる(infra/local/README.md「イメージの更新」)。
ARG DEBIAN_IMAGE
FROM ${DEBIAN_IMAGE}

# DEBIAN_SUITE は、ベースの Debian の版の名前(下で /etc/os-release と照らす)。check-pins.py はこの名前で版の一覧を読む。
# パッケージの版は <名前>_DEB_VERSION にし、`apt-get install` で `パッケージ=${<名前>_DEB_VERSION}` の形で使う(check-pins.py が読む)
ARG DEBIAN_SUITE=trixie
ARG GPG_DEB_VERSION=2.4.7-21+deb13u1+b5

RUN . /etc/os-release && test "$VERSION_CODENAME" = "$DEBIAN_SUITE" \
    && apt-get update \
    && apt-get install -y --no-install-recommends gpg=${GPG_DEB_VERSION} gpg-agent=${GPG_DEB_VERSION} gpgconf=${GPG_DEB_VERSION} \
    && rm -rf /var/lib/apt/lists/*

# 鍵束は呼び出しごとの一時ディレクトリ(GNUPGHOME)に作る。鍵は標準入出力とマウントしたディレクトリだけで受け渡す
RUN useradd --create-home --uid 1000 gnupg
USER gnupg
ENV GNUPGHOME=/tmp/gnupg
ENTRYPOINT ["gpg"]

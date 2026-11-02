#!/usr/bin/with-contenv bash
# shellcheck shell=bash
# linuxserver/openssh-server の起動時フック(/custom-cont-init.d)。取引先(sftp-b2b)の EDI の受け渡し用ディレクトリを作る(ADR-0040 §4・§7)。
# to-eiaf: 取引先が受注の文書を置く(b2b-gateway が見回る。processing・processed は b2b-gateway が作る) / from-eiaf: 応答(CONTRL・APERAK)
for dir in to-eiaf from-eiaf; do
  mkdir -p "/config/$dir"
  chown "${PUID:-1000}:${PGID:-1000}" "/config/$dir"
done

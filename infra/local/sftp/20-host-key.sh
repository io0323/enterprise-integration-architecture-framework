#!/usr/bin/with-contenv bash
# shellcheck shell=bash
# linuxserver/openssh-server の起動時フック(/custom-cont-init.d)。sftp-b2b のホスト鍵(ed25519)を、make env が作った鍵
# (secrets/sftp-b2b-host-key)に置き換える(P09 ⑥。ADR-0032 §8)。接続する側(file-exchange・make verify)は known_hosts で
# この鍵に固定するので、コンテナやボリュームを作り直してもホスト鍵は変わらない。鍵がマウントされていなければ何もしない(b2b profile の検査用)。
src=/keys/host/ssh_host_ed25519_key
dst=/config/ssh_host_keys/ssh_host_ed25519_key
[[ -f "$src" ]] || exit 0
mkdir -p /config/ssh_host_keys
cp "$src" "$dst" && cp "$src.pub" "$dst.pub"
chown "${PUID:-1000}:${PGID:-1000}" "$dst" "$dst.pub"
chmod 600 "$dst"
chmod 644 "$dst.pub"

#!/usr/bin/with-contenv bash
# linuxserver/openssh-server の起動時フック(/custom-cont-init.d)。SFTP の受け渡し用ディレクトリを作る(Framework 9.2)。
# inbox: 受信(相手が一時名で置き、完了後に正式名へリネームする) / outbox: 送信 / archive: 処理済みの原本
for dir in inbox outbox archive; do
  mkdir -p "/config/$dir"
  chown "${PUID:-1000}:${PGID:-1000}" "/config/$dir"
done

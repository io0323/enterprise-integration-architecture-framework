package io.eia.platform.reliability

// 違反: SFTP(sshj)と OpenPGP は platform/file-transfer だけ(ADR-0032 §8)
import net.schmizz.sshj.SSHClient

class UsesSftpAndPgp(
    val ssh: SSHClient,
    val keys: org.bouncycastle.openpgp.PGPPublicKeyRing,
)

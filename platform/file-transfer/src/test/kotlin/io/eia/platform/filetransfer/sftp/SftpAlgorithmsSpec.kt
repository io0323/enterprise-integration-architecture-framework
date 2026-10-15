package io.eia.platform.filetransfer.sftp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class SftpAlgorithmsSpec :
    FunSpec({
        test("sshj の設定は、許可した方式だけを一覧の順に持つ") {
            val config = SftpAlgorithms.config()
            config.keyExchangeFactories.map { it.name } shouldContainExactly SftpAlgorithms.KEY_EXCHANGE
            config.keyAlgorithms.map { it.name } shouldContainExactly SftpAlgorithms.KEY
            config.cipherFactories.map { it.name } shouldContainExactly SftpAlgorithms.CIPHER
            config.macFactories.map { it.name } shouldContainExactly SftpAlgorithms.MAC
        }

        test("古い方式と、Terrapin の影響を受ける方式を含まない") {
            val all = SftpAlgorithms.KEY_EXCHANGE + SftpAlgorithms.KEY + SftpAlgorithms.CIPHER + SftpAlgorithms.MAC
            val forbidden =
                listOf("sha1", "ssh-rsa", "ssh-dss", "cbc", "arcfour", "md5", "3des", "chacha20", "etm", "group1-", "group-exchange")
            all.filter { name -> forbidden.any { it in name } } shouldBe emptyList()
        }
    })

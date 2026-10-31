package io.eia.platform.testsupport

/**
 * テスト用の取引先の台帳(platform/trading-partner の形。ADR-0040)の JSON。ローカル基盤の台帳(infra/local/b2b/partners.json)と同じ項目で、
 * 接続先と秘密情報の名前だけを変えられる。
 */
public object TestLedgers {
    /**
     * 台帳の取引先 1 件。
     *
     * @property datasets 送るファイルのデータセット(INT-SALES-004)。空なら送らない
     * @property edi true なら EDI の文書を受け取り、応答を返す(INT-B2B-001〜003。送り手の識別子・ディレクトリ・書き終わりの判断を書く)
     */
    public data class Partner(
        val id: String = "partner01",
        val host: String = "sftp-b2b",
        val port: Int = 2222,
        val username: String = "partner01",
        val outbound: String = "/config/outbox",
        val datasets: List<String> = listOf("sales_daily"),
        val knownHostsSecret: String = "P01_KNOWN_HOSTS",
        val privateKeySecret: String = "P01_SSH_KEY",
        val passphraseSecret: String? = null,
        val pgpPublicKeySecret: String = "P01_PGP_PUBLIC_KEY",
        val edi: Boolean = true,
        val edifactId: String = "4912345000019",
        val positiveAperak: Boolean = true,
        val validUntil: String = "2099-12-31",
        // 署名の鍵の差し替えの間の旧い公開鍵(秘密情報の名前 → 受け付ける期日)
        val previousPgpPublicKeys: Map<String, String> = emptyMap(),
    )

    public fun json(vararg partners: Partner): String =
        """
        {"version": 1,
         "company": {"name": "EIAF (test)", "edifact": {"id": "4900000000001", "qualifier": "14"}, "gln": "4900000000001"},
         "partners": [${partners.joinToString(",") { partner(it) }}]}
        """.trimIndent()

    private fun partner(p: Partner): String {
        val documents =
            p.datasets.map { """{"integration": "INT-SALES-004", "direction": "outbound", "dataset": "$it"}""" } +
                if (p.edi) {
                    listOf("INT-B2B-001" to "inbound", "INT-B2B-002" to "outbound", "INT-B2B-003" to "outbound").map { (id, direction) ->
                        """{"integration": "$id", "direction": "$direction"}"""
                    }
                } else {
                    emptyList()
                }
        val directories =
            listOfNotNull(
                """"outbound": "${p.outbound}"""".takeIf { p.datasets.isNotEmpty() },
                """"inbound": "/config/to-eiaf"""".takeIf { p.edi },
                """"acknowledgements": "/config/from-eiaf"""".takeIf { p.edi },
            )
        val passphrase = p.passphraseSecret?.let { """, "passphraseSecret": "$it"""" }.orEmpty()
        val previous =
            if (p.previousPgpPublicKeys.isEmpty()) {
                ""
            } else {
                """, "previousPublicKeys": [${p.previousPgpPublicKeys.entries.joinToString(",") { (name, until) ->
                    """{"publicKeySecret": "$name", "acceptUntil": "$until"}"""
                }}]"""
            }
        return """
            {"id": "${p.id}", "name": "${p.id} (test)",
             ${if (p.edi) """"edifact": {"id": "${p.edifactId}", "qualifier": "14"},""" else ""}
             "documents": [${documents.joinToString(",")}],
             "sftp": {"host": "${p.host}", "port": ${p.port}, "username": "${p.username}",
                      "hostKey": {"knownHostsSecret": "${p.knownHostsSecret}", "validUntil": "${p.validUntil}"},
                      "clientKey": {"privateKeySecret": "${p.privateKeySecret}"$passphrase, "validUntil": "${p.validUntil}"},
                      "directories": {${directories.joinToString(",")}}
                      ${if (p.edi) """, "completion": {"method": "rename", "temporarySuffix": ".part"}""" else ""}},
             "pgp": {"publicKeySecret": "${p.pgpPublicKeySecret}", "validUntil": "${p.validUntil}"$previous},
             "agreement": {"currencies": ["JPY"],
                           "acknowledgements": {"positiveAperak": ${p.positiveAperak}, "aperakDeadline": "PT4H"},
                           "cutoff": {"time": "15:00", "timeZone": "Asia/Tokyo"},
                           "sla": {"syntaxAcknowledgement": "PT15M"},
                           "resend": "test"},
             "contacts": [{"role": "operations", "name": "${p.id} operations", "email": "ops@${p.id}.example"}]}
            """.trimIndent()
    }
}

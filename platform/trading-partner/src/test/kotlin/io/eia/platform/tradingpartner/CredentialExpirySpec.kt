package io.eia.platform.tradingpartner

import io.eia.shared.kernel.Result
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import java.time.Instant
import java.time.ZoneOffset

private val LEDGER: TradingPartnerLedger = (TradingPartnerLedger.parse(LOCAL_LEDGER) as Result.Ok).value

/** 台帳の鍵の期日(VALID_UNTIL)の日の終わり(UTC)。 */
private val AGREED: Instant = VALID_UNTIL.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()

class CredentialExpirySpec :
    FunSpec({
        test("台帳の鍵ごとの期限(validUntil の日の終わり。UTC)") {
            CredentialExpiries.of(LEDGER) shouldContainExactlyInAnyOrder
                listOf(
                    CredentialExpiry("partner01", CredentialKind.SFTP_HOST_KEY, AGREED),
                    CredentialExpiry("partner01", CredentialKind.SFTP_CLIENT_KEY, AGREED),
                    CredentialExpiry("partner01", CredentialKind.PGP_PUBLIC_KEY, AGREED),
                )
        }

        test("鍵の中の期限が台帳の期日より早ければ、そちらを使う(遅ければ台帳の期日)") {
            val early = AGREED.minusSeconds(86_400 * 30)
            val late = AGREED.plusSeconds(86_400 * 30)

            CredentialExpiries
                .of(LEDGER) { _, kind -> if (kind == CredentialKind.PGP_PUBLIC_KEY) early else late }
                .associate { it.kind to it.expiresAt } shouldBe
                mapOf(
                    CredentialKind.SFTP_HOST_KEY to AGREED,
                    CredentialKind.SFTP_CLIENT_KEY to AGREED,
                    CredentialKind.PGP_PUBLIC_KEY to early,
                )
        }

        test("メトリクス eia.trading_partner.credential.expiry は、取引先と鍵の種類ごとの期限(UNIX 秒)") {
            val reader = InMemoryMetricReader.create()
            val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
            CredentialExpiryMetrics(provider.get("test")) { CredentialExpiries.of(LEDGER) }.use {
                val points =
                    reader.collectAllMetrics().single { it.name == CredentialExpiryMetrics.NAME }.doubleGaugeData.points.associate {
                        it.attributes.get(CredentialExpiryMetrics.CREDENTIAL) to it.value
                    }

                points shouldBe CredentialKind.entries.associate { it.label to AGREED.epochSecond.toDouble() }
            }
        }
    })

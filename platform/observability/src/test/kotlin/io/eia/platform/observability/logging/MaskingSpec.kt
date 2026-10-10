package io.eia.platform.observability.logging

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val JWT =
    "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJvcmRlci1zZXJ2aWNlIiwiYXVkIjoib3JkZXItYXBpIn0.c2lnbmF0dXJlLXZhbHVl"

class MaskingSpec :
    FunSpec({
        context("トークン") {
            test("JWT を伏せる(ヘッダの値でも本文の途中でも)") {
                Masking.mask("token=$JWT end") shouldBe "token=*** end"
                Masking.mask("received $JWT from keycloak") shouldBe "received [token] from keycloak"
            }

            test("Bearer / Basic の資格情報を伏せ、方式の名前は残す") {
                Masking.mask("Authorization: Bearer abc.def-ghi") shouldBe "Authorization: ***"
                Masking.mask("header bearer abc123def456 sent") shouldBe "header bearer [token] sent"
                Masking.mask("Basic dXNlcjpwYXNz") shouldBe "Basic [token]"
            }
        }

        context("秘密情報のキー") {
            test("JSON の値を伏せる(エスケープした引用符を含む値も)") {
                Masking.mask("""{"user":"u1","password":"p\"w,d","client_secret" : "s3"}""") shouldBe
                    """{"user":"u1","password":"***","client_secret" : "***"}"""
            }

            test("key=value とクエリ文字列の値を伏せる") {
                Masking.mask("grant_type=client_credentials&client_secret=s3cr3t&scope=x") shouldBe
                    "grant_type=client_credentials&client_secret=***&scope=x"
                Masking.mask("api_key: k-123, next") shouldBe "api_key: ***, next"
                Masking.mask("PASSWORD=Hunter2") shouldBe "PASSWORD=***"
            }
        }

        context("秘密情報のキー(レビューで見つかった取りこぼし)") {
            test("camelCase・ハイフン区切り・前後に語が付いたキー") {
                Masking.mask("""{"accessToken":"opaque-abc","clientSecret":"s3","refreshToken":"r1"}""") shouldBe
                    """{"accessToken":"***","clientSecret":"***","refreshToken":"***"}"""
                Masking.mask("accessToken=opaque-abc clientSecret=s3") shouldBe "accessToken=*** clientSecret=***"
                Masking.mask("x-api-key: k1 api-key=k2") shouldBe "x-api-key: *** api-key=***"
                Masking.mask("newPassword=n1 passwordHash=h1") shouldBe "newPassword=*** passwordHash=***"
            }

            test("引用符で囲んだ値(二重・一重)と、一重引用符のキー") {
                Masking.mask("""password="hunter2" password: "hunter3" token="abc"""") shouldBe
                    """password="***" password: "***" token="***""""
                Masking.mask("{'password': 'hunter2'}") shouldBe "{'password': '***'}"
            }

            test("URL の userinfo") {
                Masking.mask("connect jdbc:postgresql://eia:pw123@db:5432/x") shouldBe "connect jdbc:postgresql://***:***@db:5432/x"
            }

            test("値の後ろの ) は残す(toString の形を壊さない)") {
                Masking.mask("LoginRequest(user=a, password=hunter2)") shouldBe "LoginRequest(user=a, password=***)"
            }
        }

        context("入れ子の値・空白区切りのキー") {
            test("配列と 1 段のオブジェクトの値は中身ごと伏せ、JSON の形を保つ") {
                Masking.mask("""{"tokens": ["t1","t2"], "n": 1}""") shouldBe """{"tokens": "***", "n": 1}"""
                Masking.mask("""{"credentials": {"user":"a","password":"x"}}""") shouldBe """{"credentials": "***"}"""
            }

            test("2 段の入れ子・括弧が対応しない値・閉じない引用符") {
                Masking.mask("""{"credentials": {"basic": {"user":"u","pass":"p"}}}""") shouldBe """{"credentials": "***"}"""
                Masking.mask("""{"tokens": [["a"],["b"]]}""") shouldBe """{"tokens": "***"}"""
                Masking.mask("secret={broken, token=[x\nnext line") shouldBe "secret=\"***\"\nnext line"
                Masking.mask("password='abc-unterminated") shouldBe "password='***'"
            }

            test("空白で区切ったキー(api key / access key)") {
                Masking.mask("api key: xyz access key: AKIA123") shouldBe "api key: *** access key: ***"
            }
        }

        context("処理時間(長い入力で入力長の 2 乗の時間がかからない)") {
            // 入力は MAX_INPUT_LENGTH(64KB)まで伏せるため、正規表現を 64KB 近い入力で測る。
            // 入力長の 2 乗の時間がかかる正規表現(レビューで見つかった版)は 16KB で約 4 秒、64KB では 1 分以上かかり、この上限を超える
            val budget = 2.seconds
            val random = kotlin.random.Random(42)
            val base64url = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            listOf(
                "a- の繰り返し" to "a-".repeat(32 * 1024),
                "ランダムな base64url" to (1..64 * 1024).map { base64url[random.nextInt(base64url.length)] }.joinToString(""),
                "@ のない英数字の連続" to "a.".repeat(32 * 1024),
                "スキームらしい語の連続" to "http+".repeat(12 * 1024),
            ).forEach { (name, input) ->
                test("$name(${input.length} 文字)") {
                    val elapsed = kotlin.time.measureTime { Masking.mask(input) }

                    (elapsed < budget) shouldBe true
                }
            }

            listOf(
                "閉じない見出しの連続" to "-----BEGIN A-----".repeat(3_800),
                "見出しの形でない BEGIN の連続" to "-----BEGIN a".repeat(5_000),
                "形の崩れた END の連続" to ("-----BEGIN A-----" + "-----END a".repeat(6_000)),
                "ラベルの長すぎる見出しの連続" to ("-----BEGIN " + "A".repeat(70)).repeat(800),
            ).forEach { (name, input) ->
                test("PEM: $name(${input.length} 文字)") {
                    val elapsed = kotlin.time.measureTime { Masking.mask(input) }

                    (elapsed < budget) shouldBe true
                }
            }

            // PEM の走査([PemBlocks])で入力長の 2 乗の時間がかかる不具合(見出しごとに入力の末尾まで走査し直すなど)の再発を防ぐ。
            // 同じ文字数を処理する時間で比べる: 1/16 の長さの入力を 16 回処理する時間と、全長の入力を 1 回処理する時間(それぞれ rounds 回繰り返す)。
            // 入力長に比例するなら比は約 1、2 乗なら約 16 になる。しきい値はその幾何平均の 4 にし、どちらの側にも 4 倍の余裕を持たせる。
            // - PemBlocks を直接測る。Masking.mask 全体で測ると、ほかの規則の(入力長に比例する)時間で差が薄まるため
            // - 並列ビルドの負荷に強くするため、両方の計測を同じ長さにして分母のぶれを小さくし、小さい入力と大きい入力を交互に測り
            //   (負荷の変化が両方に同じように効く)、最小値で比べる
            test("PEM: 同じ文字数なら、短い入力を繰り返しても長い入力 1 回でも時間は同程度(入力長の 2 乗なら 16 倍)") {
                val factor = 16
                val rounds = 8 // 1 回の計測をミリ秒単位にして、計測のぶれ(割り込み・GC)の影響を小さくする
                listOf("-----BEGIN a", "-----BEGIN A-----x-----END a").forEach { unit ->
                    val small = unit.repeat(Masking.MAX_INPUT_LENGTH / factor / unit.length)
                    val large = small.repeat(factor)
                    repeat(3) { PemBlocks.mask(large, "***") } // JIT のウォームアップ
                    val smallTimes = mutableListOf<Long>()
                    val largeTimes = mutableListOf<Long>()
                    repeat(15) {
                        smallTimes +=
                            kotlin.time.measureTime { repeat(rounds * factor) { PemBlocks.mask(small, "***") } }.inWholeMicroseconds
                        largeTimes += kotlin.time.measureTime { repeat(rounds) { PemBlocks.mask(large, "***") } }.inWholeMicroseconds
                    }
                    val ratio = largeTimes.min().coerceAtLeast(1).toDouble() / smallTimes.min().coerceAtLeast(1)

                    withClue(
                        "単位 '$unit': 比 $ratio(長い入力 $rounds 回 ${largeTimes.min()}µs / 短い入力 ${rounds * factor} 回 ${smallTimes.min()}µs)",
                    ) {
                        ratio shouldBeLessThan 4.0
                    }
                }
            }

            test("JWT らしい語の連続(eyJ-)") {
                val elapsed = kotlin.time.measureTime { Masking.mask("eyJ-".repeat(16 * 1024)) }

                (elapsed < budget) shouldBe true
            }

            test("${Masking.MAX_LENGTH} 文字を超えた分は、先頭と末尾を残して切り詰める(末尾の Caused by: を残す)") {
                val half = Masking.MAX_LENGTH / 2
                val masked = Masking.mask("x".repeat(Masking.MAX_LENGTH + 10) + "Caused by: java.io.IOException: root")

                masked.startsWith("x".repeat(half) + "…[truncated ") shouldBe true
                masked.endsWith("Caused by: java.io.IOException: root") shouldBe true
            }
        }

        context("PEM の塊") {
            val body = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7"

            test("引用符のない toString() の値でも、見出しから終わりまで丸ごと伏せる(レビューで見つかった見逃し)") {
                val input = "Creds(privateKey=-----BEGIN PRIVATE KEY-----\n$body\n$body\n-----END PRIVATE KEY-----, user=a)"

                Masking.mask(input) shouldBe "Creds(privateKey=***, user=a)"
            }

            test("改行の種類・JSON のエスケープ・改行なしのどれでも伏せる") {
                val crlf = "key:\r\n-----BEGIN RSA PRIVATE KEY-----\r\n$body\r\n-----END RSA PRIVATE KEY-----\r\nnext"
                val json = """{"pem":"-----BEGIN EC PRIVATE KEY-----\\n$body\\n-----END EC PRIVATE KEY-----\\n","n":1}"""
                val oneLine = "-----BEGIN PRIVATE KEY-----$body-----END PRIVATE KEY----- tail"

                Masking.mask(crlf) shouldBe "key:\r\n***\r\nnext"
                Masking.mask(json) shouldNotContain body
                Masking.mask(json) shouldContain "\"n\":1"
                Masking.mask(oneLine) shouldBe "*** tail"
            }

            test("キーのない塊・複数の塊・閉じない塊(入力の末尾まで)") {
                Masking.mask(
                    "a -----BEGIN CERTIFICATE-----$body-----END CERTIFICATE----- b -----BEGIN X-----$body-----END X----- c",
                ) shouldBe
                    "a *** b *** c"
                Masking.mask("head -----BEGIN OPENSSH PRIVATE KEY-----\n$body\n(truncated") shouldBe "head ***"
            }

            test("見出しの形でないものは伏せない(ラベルは英大文字・数字・空白の 1〜64 文字)") {
                Masking.mask("-----BEGIN lower-----x") shouldBe "-----BEGIN lower-----x"
                Masking.mask("-----BEGIN -----x") shouldBe "-----BEGIN -----x"
                Masking.mask("----- separator -----") shouldBe "----- separator -----"
            }
        }

        context("切り詰めの境目で秘密情報が漏れない") {
            test("伏せてから切り詰めるので、境目でキーと値が分かれても値は残らない") {
                // 伏せた後の長さの中央付近に password= が来るように置く
                val secret = "Sup3rS3cretV4lue"
                listOf(Masking.MAX_LENGTH / 2 - 9, Masking.MAX_LENGTH / 2 - 5, Masking.MAX_LENGTH / 2).forEach { offset ->
                    val input = "x".repeat(offset) + "password=$secret " + "y".repeat(Masking.MAX_LENGTH * 2)

                    Masking.mask(input) shouldNotContain "cretV4lue"
                }
            }

            test("入力の上限を超えた分の末尾は、改行の次から始める(キーのない値を残さない)") {
                val secret = "Sup3rS3cretV4lue"
                val half = Masking.MAX_INPUT_LENGTH / 2
                val trailer = "Caused by: java.io.IOException: root"
                // 末尾に残す half 文字がちょうど値から始まるように置く(入力の上限の境目が password= と値の間に来る)
                val rest = secret + "\n" + "r".repeat(half - secret.length - 2 - trailer.length) + "\n" + trailer
                val input = "z".repeat(half + 100) + "password=" + rest
                val masked = Masking.mask(input)

                rest.length shouldBe half
                masked shouldNotContain "cretV4lue"
                masked.endsWith(trailer) shouldBe true
            }

            // #33: 入力の上限の先頭側の切れ目(MAX_INPUT_LENGTH / 2)にかかった値の断片を残さない。
            // 切れ目の後に改行がないと末尾側は捨てられ、先頭側の終わりが最後の切り詰めの後も出力に残る
            val values =
                mapOf(
                    "メールアドレス" to ("hanako.sato@example.com" to listOf("hanako", "sato@", "@example")),
                    "電話番号(ハイフン)" to ("090-1234-5678" to listOf("090-", "1234-5")),
                    "電話番号(空白・国番号)" to ("+81 90 1234 5678" to listOf("+81 90", "90 1234")),
                    "カード番号(区切りなし)" to ("4111111111111111" to listOf("41111111")),
                    "カード番号(空白区切り)" to ("4111 1111 1111 1111" to listOf("4111 1111", "1111 1111 1111")),
                    "JWT" to (
                        "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEifQ.c2lnbmF0dXJlLXNpZ25hdHVyZS1zaWduYXR1cmU" to
                            listOf("eyJhbGciOiJS", "eyJzdWIi")
                    ),
                )
            values.forEach { (name, case) ->
                val (value, fragments) = case
                test("#33: 入力の上限の切れ目にかかった値の断片を残さない: $name") {
                    val half = Masking.MAX_INPUT_LENGTH / 2
                    // 切れ目が値の 1 文字目の後・中央・最後の 1 文字の前に来るように置く
                    listOf(1, value.length / 2, value.length - 1).forEach { inside ->
                        val prefix = "word ".repeat(half / 5 + 1).take(half - inside - 1) + " "
                        val input = prefix + value + " " + "y".repeat(Masking.MAX_INPUT_LENGTH)
                        val masked = Masking.mask(input)

                        fragments.forEach { fragment -> masked shouldNotContain fragment }
                        masked shouldContain "[truncated "
                    }
                }
            }

            test("#33: 切れ目の前に区切り文字がなければ、先頭側の連続を丸ごと捨てる(処理は入力長に比例)") {
                val input = "a".repeat(Masking.MAX_INPUT_LENGTH) + "hanako.sato@example.com" + "b".repeat(Masking.MAX_INPUT_LENGTH)
                val started = TimeSource.Monotonic.markNow()
                val masked = Masking.mask(input)

                masked shouldNotContain "aaaa"
                masked shouldNotContain "hanako"
                started.elapsedNow() shouldBeLessThan 1.seconds
            }

            test("大きな配列(4,096 文字超)の値も末尾まで伏せる") {
                val masked = Masking.mask("""{"tokens": [""" + """"t",""".repeat(1_500) + """"LAST-SECRET"]}""")

                masked shouldNotContain "LAST-SECRET"
            }

            test("pass は語全体のときだけ秘密情報のキーにする") {
                Masking.mask("tests passed: 12 of 12, bypass=true, passengerCount: 3") shouldBe
                    "tests passed: 12 of 12, bypass=true, passengerCount: 3"
                Masking.mask("db_pass=p1 passphrase: p2") shouldBe "db_pass=*** passphrase: ***"
            }
        }

        context("長い値でもスタックがあふれない(スタック 1MB のスレッド。Linux x64 の既定)") {
            val pem = "-----BEGIN PRIVATE KEY-----" + "MIIEvQIBADANBgkqhkiG9w0BAQEFAASC".repeat(60)
            listOf(
                "引用符で囲んだ 16KB のアクセストークン" to ("""{"access_token":"""" + "a".repeat(16_000) + """"}"""),
                "閉じない一重引用符の PEM" to "private_key='$pem",
                "エスケープの多い値" to ("""password="""" + "\\a".repeat(3_000) + "\""),
                "16KB の本文の PEM" to
                    ("-----BEGIN PRIVATE KEY-----\n" + "MIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n".repeat(500) + "-----END PRIVATE KEY-----"),
            ).forEach { (name, input) ->
                test(name) {
                    var masked: String? = null
                    var thrown: Throwable? = null
                    val thread =
                        Thread(null, {
                            try {
                                masked = Masking.mask(input)
                            } catch (
                                @Suppress("TooGenericExceptionCaught") e: Throwable,
                            ) {
                                thrown = e
                            }
                        }, "small-stack", 1L shl 20)
                    thread.start()
                    thread.join()

                    thrown shouldBe null
                    val result = checkNotNull(masked)

                    result shouldNotContain "aaaaaaaaaa"
                    result shouldNotContain "MIIEvQIBADANBgkqhkiG9w0BAQEFAASC"
                    result shouldNotContain Masking.FAILED
                }
            }
        }

        context("誤検知しない") {
            test("空白区切りの日付と、URL のクエリの @ はそのまま") {
                Masking.mask("on 09 27 2026") shouldBe "on 09 27 2026"
                Masking.mask("GET http://host:8080?x=a@b") shouldBe "GET http://host:8080?x=a@b"
            }

            test("キーのない bearer / basic は、資格情報らしい値だけを伏せる") {
                Masking.mask("Using basic authentication") shouldBe "Using basic authentication"
                Masking.mask("Bearer tokens are rotated") shouldBe "Bearer tokens are rotated"
            }

            test("0 始まりの 10 桁の ID は電話番号にしない(区切りのない携帯の 11 桁だけを伏せる)") {
                Masking.mask("batch 0123456789") shouldBe "batch 0123456789"
                Masking.mask("tel 090 1234 5678") shouldBe "tel [phone]"
            }
        }

        context("個人情報") {
            test("メールアドレス") {
                Masking.mask("customer taro.yamada+test@example.co.jp placed") shouldBe "customer [email] placed"
            }

            test("カード番号は Luhn に通るものだけを伏せる(注文番号などの数字の列は残す)") {
                Masking.mask("card 4111 1111 1111 1111 ok") shouldBe "card [card] ok"
                Masking.mask("card 4111-1111-1111-1111") shouldBe "card [card]"
                Masking.mask("order 4111111111111112") shouldBe "order 4111111111111112"
            }

            test("電話番号(E.164・区切りのある番号・国内の 10〜11 桁)") {
                Masking.mask("tel +81 90 1234 5678.") shouldBe "tel [phone]."
                Masking.mask("tel 03-1234-5678") shouldBe "tel [phone]"
                Masking.mask("tel 09012345678") shouldBe "tel [phone]"
            }
        }

        test("ID・時刻・トレースの値は変えない") {
            val safe =
                "order_id=ord-20260927-001 trace_id=4bf92f3577b34da6a3ce929d0e0e4736 at 2026-09-27T12:00:00Z took 123ms status=200"
            Masking.mask(safe) shouldBe safe
            Masking.mask("") shouldBe ""
        }

        test("機密のヘッダは値を丸ごと伏せ、ほかのヘッダは mask を通す") {
            Masking.maskHeader("Authorization", "Bearer x") shouldBe "***"
            Masking.maskHeader("Cookie", "session=abc") shouldBe "***"
            Masking.maskHeader("X-Request-From", "a@example.com") shouldBe "[email]"
        }

        test("複数の種類が混ざった文字列から、どれも漏らさない") {
            val masked = Masking.mask("""login {"email":"a@example.com","password":"pw1"} Bearer $JWT card=4111111111111111""")

            listOf("a@example.com", "pw1", JWT, "4111111111111111").forEach { masked shouldNotContain it }
        }
    })

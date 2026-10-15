package io.eia.platform.filetransfer.write

import io.eia.platform.filetransfer.layout.CsvSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CsvFilesSpec :
    FunSpec({
        fun count(
            text: String,
            csv: CsvSettings = CsvSettings.RFC4180,
        ) = CsvFiles.count(text.byteInputStream(), csv)

        test("レコードはヘッダを除いて数える。囲みの中の改行・区切り・囲みの重ねはレコードを分けない") {
            val counted = count("a,b\r\n\"x\r\ny\",\"1,\"\"2\"\"\"\r\n3,4\r\n")
            counted shouldBe CsvCount(records = 2, header = listOf("a", "b"), malformed = false)
        }

        test("ヘッダだけ・空のファイルは 0 件") {
            count("a,b\r\n") shouldBe CsvCount(0, listOf("a", "b"), false)
            count("").records shouldBe 0
        }

        test("囲みが閉じていない・最後のレコードが改行で終わらないファイルは形式の誤り") {
            count("a,b\r\n\"open,1\r\n").malformed shouldBe true
            count("a,b\r\n1,2").malformed shouldBe true
        }

        test("ヘッダのない形式は、全部のレコードを数える") {
            count("1,2\n3,4\n", CsvSettings(header = false, lineSeparator = "\n")) shouldBe CsvCount(2, null, false)
        }
    })

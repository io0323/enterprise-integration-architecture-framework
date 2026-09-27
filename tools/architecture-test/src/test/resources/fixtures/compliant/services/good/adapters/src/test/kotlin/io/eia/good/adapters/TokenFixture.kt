package io.eia.good.adapters

// 準拠: テストのソースセットは、テスト用のトークンを作るために Nimbus を使える
import com.nimbusds.jwt.PlainJWT

class TokenFixture {
    // 文字列の中の "com.nimbusds." は参照ではない
    val note = "com.nimbusds.jwt"

    fun unsigned(): PlainJWT = PlainJWT(com.nimbusds.jwt.JWTClaimsSet.Builder().build())
}

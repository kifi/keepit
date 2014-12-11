package com.keepit.common.oauth

import com.keepit.model.OAuth2TokenInfo
import org.specs2.mutable.Specification
import play.api.libs.json.Json

class OAuth2TokenInfoTest extends Specification {

  val tk = "SlAV32hkKG"
  val tkType = "example"
  val expires = 3600
  val refreshTk = "8xLOxBtZp8"
  val expected = OAuth2TokenInfo(OAuth2AccessToken(tk), Some(tkType), Some(expires), Some(refreshTk))

  "OAuth2TokenInfo" should {
    "parse old (camelCase) format" in {
      val json =
        s"""
          {
           "accessToken":"$tk",
           "tokenType":"$tkType",
           "expiresIn":$expires,
           "refreshToken":"$refreshTk"
          }
         """.stripMargin
      Json.parse(json).as[OAuth2TokenInfo] === expected

      val minimal =
        s"""
          {
           "accessToken":"$tk"
          }
         """.stripMargin
      Json.parse(minimal).as[OAuth2TokenInfo].accessToken === expected.accessToken
    }

    "parse new (standard) format" in {
      val json =
        s"""
          {
           "access_token":"$tk",
           "token_type":"$tkType",
           "expires_in":$expires,
           "refresh_token":"$refreshTk"
          }
         """.stripMargin
      Json.parse(json).as[OAuth2TokenInfo] === expected
    }
  }

}

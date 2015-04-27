package com.keepit.common.social

import com.keepit.common.strings.UTF8
import java.io.File
import java.nio.charset.StandardCharsets._
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import org.specs2.mutable._

import com.keepit.common.db.slick.Database
import com.keepit.common.net.{ FakeHttpClient, DirectUrl }
import com.keepit.common.time._
import com.keepit.model.{ Username, SocialUserInfo, SocialUserInfoRepo, User }
import com.keepit.social.{ SocialId, SocialNetworks }
import com.keepit.test._

import oauth.signpost.exception.OAuthExpectationFailedException

import org.apache.commons.codec.binary.Base64

import play.api.libs.json.Json

import scala.util.Success

import securesocial.core.{ AuthenticationMethod, IdentityId, OAuth2Info, OAuth2Settings, SocialUser }

class FacebookSocialGraphTest extends Specification with ShoeboxTestInjector {

  "FacebookSocialGraph" should {

    "find pagination url" in {
      withDb() { implicit injector =>
        val graph = new FacebookSocialGraph(new FakeHttpClient(), db, null, null, socialUserInfoRepo, null)
        val eishay1Json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/facebook_graph_eishay_min_page1.json"), UTF8).mkString)
        graph.nextPageUrl(eishay1Json) === Some("https://graph.facebook.com/646386018/friends?fields=name,gender,username,email,picture&access_token=AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD&limit=5000&offset=5000&__after_id=100004067535411")
      }
    }

    "not find pagination url" in {
      withDb() { implicit injector =>
        val graph = new FacebookSocialGraph(new FakeHttpClient(), db, null, null, socialUserInfoRepo, null)
        val eishay2Json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/data/facebook_graph_eishay_min_page2.json"), UTF8).mkString)
        graph.nextPageUrl(eishay2Json) === None
      }
    }

    "fetch from facebook" in {
      withDb() { implicit injector =>
        val expectedUrl = DirectUrl("https://graph.facebook.com/v2.0/646386018?access_token=AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD&fields=name,first_name,middle_name,last_name,gender,email,picture.type(large),friends.fields(name).limit(500)")
        val json = io.Source.fromFile(new File("test/com/keepit/common/social/data/facebook_graph_eishay_super_min.json"), UTF8).mkString
        val httpClient = new FakeHttpClient(Some(Map(expectedUrl -> json)))

        val oAuth2Info = OAuth2Info(accessToken = "AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD",
          tokenType = None, expiresIn = None, refreshToken = None)
        val socialUser = SocialUser(IdentityId("100004067535411", "facebook"), "Boaz", "Tal", "Boaz Tal", Some("boaz.tal@gmail.com"),
          Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None, Some(oAuth2Info), None)

        val user = inject[Database].readWrite { implicit s =>
          userRepo.save(User(firstName = "Eishay", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
        }
        val unsaved = SocialUserInfo(userId = user.id, fullName = "Eishay Smith", socialId = SocialId("646386018"), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser))
        val socialUserInfo = inject[Database].readWrite { implicit s =>
          inject[SocialUserInfoRepo].save(unsaved)
        }
        unsaved.userId === user.id
        socialUserInfo.userId === user.id
        socialUserInfo.fullName === "Eishay Smith"
        socialUserInfo.socialId.id === "646386018"
        socialUserInfo.credentials.get === socialUser

        val graph = new FacebookSocialGraph(httpClient, db, null, null, socialUserInfoRepo, null)
        val rawInfo = graph.fetchSocialUserRawInfo(socialUserInfo).get
        rawInfo.fullName === "Eishay Smith"
        rawInfo.userId === socialUserInfo.userId

        rawInfo.socialId.id === "646386018"
      }
    }

    "fetch from facebook using jennifer_hirsch" in {
      withDb() { implicit injector =>
        val data = io.Source.fromFile(new File("test/com/keepit/common/social/data/jennifer_hirsch.min.json"), UTF8).mkString
        val httpClient = new FakeHttpClient(Some({ case _ => data }))
        val info = SocialUserInfo(userId = None, fullName = "", socialId = SocialId(""), networkType = SocialNetworks.FACEBOOK, credentials = None)

        val graph = new FacebookSocialGraph(httpClient, db, null, null, socialUserInfoRepo, null) {
          override def getAccessToken(socialUserInfo: SocialUserInfo): String = ""
        }
        val rawInfo = graph.fetchSocialUserRawInfo(info).get
        rawInfo.socialId.id === "627689"
      }
    }

    //This is really large! Disable by default
    "fetch from facebook using grimland" in {
      skipped("json is very large, stress test only")
      withDb() { implicit injector =>
        val data = io.Source.fromFile(new File("test/com/keepit/common/social/data/large.json"), UTF8).mkString
        val httpClient = new FakeHttpClient(Some({ case _ => data }))
        val info = SocialUserInfo(userId = None, fullName = "", socialId = SocialId(""), networkType = SocialNetworks.FACEBOOK, credentials = None)

        val graph = new FacebookSocialGraph(httpClient, db, null, null, socialUserInfoRepo, null) {
          override def getAccessToken(socialUserInfo: SocialUserInfo): String = ""
        }
        val rawInfo = graph.fetchSocialUserRawInfo(info).get
        rawInfo.socialId.id === "grimland"
      }
    }

    "vet a valid JS API auth response" in {
      val userId = "10005000345440"
      val clock = new FakeClock
      val now = clock.now
      clock.push(now)
      val issuedAt = now.minusSeconds(15).getMillis / 1000
      val settings = OAuth2Settings("", "", "__app_id__", "__app_secret__", None)
      val signedRequest = signRequest(s"""{"algorithm":"HMAC-SHA256","issued_at":$issuedAt,"user_id":"$userId"}""", settings.clientSecret)
      val json = Json.obj("accessToken" -> "abcdefg...", "expiresIn" -> 3108, "signedRequest" -> signedRequest, "userID" -> userId)
      val graph = new FacebookSocialGraph(null, null, clock, null, null, null)
      graph.vetJsAccessToken(settings, json) === Success(IdentityId(userId = userId, providerId = "facebook"))
    }

    "vet a JS API auth response with bad signature" in {
      val userId = "10005000345440"
      val clock = new FakeClock
      val now = clock.now
      clock.push(now)
      val issuedAt = now.minusSeconds(15).getMillis / 1000
      val settings = OAuth2Settings("", "", "__app_id__", "__app_secret__", None)
      val signedRequest = signRequest(s"""{"algorithm":"HMAC-SHA256","issued_at":$issuedAt,"user_id":"$userId"}""", "WRONG SECRET")
      val json = Json.obj("accessToken" -> "abcdefg...", "expiresIn" -> 3108, "signedRequest" -> signedRequest, "userID" -> userId)
      val graph = new FacebookSocialGraph(null, null, clock, null, null, null)
      graph.vetJsAccessToken(settings, json) must beFailedTry.withThrowable[OAuthExpectationFailedException]("signature mismatch")
    }

    "vet an old JS API auth response" in {
      val userId = "10005000345440"
      val clock = new FakeClock
      val now = clock.now
      clock.push(now)
      val issuedAt = now.minusHours(2).getMillis / 1000
      val settings = OAuth2Settings("", "", "__app_id__", "__app_secret__", None)
      val signedRequest = signRequest(s"""{"algorithm":"HMAC-SHA256","issued_at":$issuedAt,"user_id":"$userId"}""", settings.clientSecret)
      val json = Json.obj("accessToken" -> "abcdefg...", "expiresIn" -> 3108, "signedRequest" -> signedRequest, "userID" -> userId)
      val graph = new FacebookSocialGraph(null, null, clock, null, null, null)
      graph.vetJsAccessToken(settings, json) must beFailedTry.withThrowable[OAuthExpectationFailedException]("7200s is too old")
    }
  }

  // helpful for generating test cases
  private def signRequest(json: String, clientSecret: String): String = {
    val base64 = new Base64(true)
    val payload = base64.encode(json.getBytes(US_ASCII))
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(clientSecret.getBytes(US_ASCII), "HmacSHA256"))
    base64.encodeToString(mac.doFinal(payload)) + "." + new String(payload, US_ASCII)
  }
}

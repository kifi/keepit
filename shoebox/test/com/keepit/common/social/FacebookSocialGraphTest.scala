package com.keepit.common.social

import scala.Some

import java.io.File

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

import com.keepit.common.db.CX
import com.keepit.common.db.slick.DBConnection
import com.keepit.common.net.FakeHttpClient
import com.keepit.inject._
import com.keepit.model.SocialUserInfo
import com.keepit.model.SocialUserInfoRepo
import com.keepit.model.User
import com.keepit.test._

import play.api.Play.current
import play.api.libs.json.Json
import play.api.test.Helpers._
import securesocial.core.AuthenticationMethod
import securesocial.core.OAuth2Info
import securesocial.core.SocialUser
import securesocial.core.UserId

@RunWith(classOf[JUnitRunner])
class FacebookSocialGraphTest extends SpecificationWithJUnit with DbRepos {

  "FacebookSocialGraph" should {

    "find pagination url" in {
      val graph = new FacebookSocialGraph(new FakeHttpClient())
      val eishay1Json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/facebook_graph_eishay_min_page1.json")).mkString)
      graph.nextPageUrl(eishay1Json) === Some("https://graph.facebook.com/646386018/friends?fields=link,name,first_name,middle_name,last_name,location,locale,gender,username,languages,third_party_id,installed,timezone,updated_time,verified,bio,birthday,devices,education,email,picture,significant_other,website,work&access_token=AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD&limit=5000&offset=5000&__after_id=100004067535411")
    }

    "not find pagination url" in {
      val graph = new FacebookSocialGraph(new FakeHttpClient())
      val eishay2Json = Json.parse(io.Source.fromFile(new File("test/com/keepit/common/social/facebook_graph_eishay_min_page2.json")).mkString)
      graph.nextPageUrl(eishay2Json) === None
    }

    "fetch from facebook" in {
      running(new EmptyApplication()) {
        //val httpClient = HttpClientImpl(timeout = 1, timeoutUnit = TimeUnit.MINUTES)
        val httpClient = new FakeHttpClient(
            expectedUrl = Some("https://graph.facebook.com/eishay?access_token=AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD&fields=link,name,first_name,middle_name,last_name,location,locale,gender,username,languages,third_party_id,installed,timezone,updated_time,verified,bio,birthday,devices,education,email,picture,significant_other,website,work,friends.fields(link,name,first_name,middle_name,last_name,location,locale,gender,username,languages,third_party_id,installed,timezone,updated_time,verified,bio,birthday,devices,education,email,picture,significant_other,website,work)"),
            expectedResponse = Some(io.Source.fromFile(new File("test/com/keepit/common/social/facebook_graph_eishay.json")).mkString)
        )
        val oAuth2Info = OAuth2Info(accessToken = "AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD",
          tokenType = None, expiresIn = None, refreshToken = None)
        val socialUser = SocialUser(UserId("100004067535411", "facebook"), "Boaz Tal", Some("boaz.tal@gmail.com"),
          Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, true, None, Some(oAuth2Info), None)

        val user = inject[DBConnection].readWrite { implicit s => 
          userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
        }
        val unsaved = SocialUserInfo(userId = user.id, fullName = "Eishay Smith", socialId = SocialId("eishay"), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser))
        val socialUserInfo = inject[DBConnection].readWrite { implicit s =>
          inject[SocialUserInfoRepo].save(unsaved)
        }
        unsaved.userId === user.id
        socialUserInfo.userId === user.id
        socialUserInfo.fullName === "Eishay Smith"
        socialUserInfo.socialId.id === "eishay"
        socialUserInfo.credentials.get === socialUser

        val graph = new FacebookSocialGraph(httpClient)
        val rawInfo = graph.fetchSocialUserRawInfo(socialUserInfo)
        rawInfo.fullName === "Eishay Smith"
        rawInfo.userId === socialUserInfo.userId

        rawInfo.socialId.id === "eishay"
      }
    }

    "fetch from facebook using jennifer_hirsch" in {
      running(new EmptyApplication()) {
        //val httpClient = HttpClientImpl(timeout = 1, timeoutUnit = TimeUnit.MINUTES)
        val httpClient = new FakeHttpClient(
            expectedResponse = Some(io.Source.fromFile(new File("test/com/keepit/common/social/jennifer_hirsch.min.json")).mkString)
        )
        val info = SocialUserInfo(userId = None, fullName = "", socialId = SocialId(""), networkType = SocialNetworks.FACEBOOK, credentials = None)

        val graph = new FacebookSocialGraph(httpClient) {
          override def getAccessToken(socialUserInfo: SocialUserInfo): String = ""
        }
        val rawInfo = graph.fetchSocialUserRawInfo(info)
        rawInfo.socialId.id === "627689"
      }
    }

  }

}

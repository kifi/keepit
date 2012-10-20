package com.keepit.common.social

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import ru.circumflex.orm._
import java.util.concurrent.TimeUnit
import com.keepit.controllers._
import com.keepit.common.db.Id
import com.keepit.common.db.CX
import com.keepit.common.db.CX._
import com.keepit.test.EmptyApplication
import com.keepit.common.net.HttpClientImpl
import com.keepit.model.User
import securesocial.core.{SocialUser, UserId, AuthenticationMethod, OAuth2Info}
import com.keepit.common.net.FakeHttpClient
import com.keepit.model.SocialUserInfo
import play.api.Play
import java.net.URL
import java.io.File

@RunWith(classOf[JUnitRunner])
class FacebookSocialGraphTest extends SpecificationWithJUnit {

  "FacebookSocialGraph" should {
    "fetch from facebook" in {
      running(new EmptyApplication()) {
        //val httpClient = HttpClientImpl(timeout = 1, timeoutUnit = TimeUnit.MINUTES)
        val httpClient = new FakeHttpClient(
            expectedUrl = Some("https://graph.facebook.com/eishay?access_token=AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD&fields=link,name,first_name,middle_name,last_name,location,locale,gender,username,languages,third_party_id,installed,timezone,updated_time,verified,bio,birthday,devices,education,email,picture,significant_other,website,work,friends.fields(link,name,first_name,middle_name,last_name,location,locale,gender,username,languages,third_party_id,installed,timezone,updated_time,verified,bio,birthday,devices,education,email,picture,significant_other,website,work)"), 
            expectedResponse = Some(io.Source.fromFile(new File("test/com/keepit/common/social/facebook_graph.json")).mkString)
        )
        val graph = new FacebookSocialGraph(httpClient)
        val oAuth2Info = OAuth2Info(accessToken = "AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD", 
          tokenType = None, expiresIn = None, refreshToken = None)
        val socialUser = SocialUser(UserId("100004067535411", "facebook"), "Boaz Tal", Some("boaz.tal@gmail.com"), 
          Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, true, None, Some(oAuth2Info), None)

        val socialUserInfo = CX.withConnection { implicit c =>
          val user = User(firstName = "Eishay", lastName = "Smith").save
          SocialUserInfo(userId = user.id, fullName = "Eishay Smith", socialId = SocialId("eishay"), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser))
        }
        val rawInfo = graph.fetchSocialUserRawInfo(socialUserInfo)
        rawInfo.fullName === "Eishay Smith"
        rawInfo.userId === socialUserInfo.userId

        rawInfo.socialId.id === "eishay"
        val model = rawInfo.toSocialUserInfo
        model.userId === rawInfo.userId
        val saved = CX.withConnection { implicit c =>
          model.save
        }
          
        CX.withConnection { implicit c =>
          SocialUserInfo.get(saved.id.get) === saved
        }
      }
    }
  }  
  
}

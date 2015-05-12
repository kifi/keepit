package com.keepit.model

import org.specs2.mutable._

import com.google.inject.Injector
import com.keepit.akka.TestAkkaSystem
import com.keepit.common.db.{ FakeSlickSessionProvider, Id }
import com.keepit.common.time._
import com.keepit.test._

import play.api.libs.json.JsObject
import play.api.libs.json.Json
import securesocial.core._
import com.keepit.social.{ SocialNetworks, SocialId }
import org.joda.time._

class SocialUserInfoTest extends Specification with ShoeboxTestInjector with TestAkkaSystem {

  def setup()(implicit injector: Injector): User = {
    db.readWrite { implicit s =>
      val oAuth2Info = OAuth2Info(accessToken = "AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD",
        tokenType = None, expiresIn = None, refreshToken = None)
      val socialUser = SocialUser(IdentityId("100004067535411", "facebook"), "Boaz", "Tal", "Boaz Tal",
        Some("boaz.tal@gmail.com"), Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None,
        Some(oAuth2Info), None)

      val user = userRepo.save(User(firstName = "Eishay", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))

      // Users that need to be processed
      socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Eishay Smith", state = SocialUserInfoStates.CREATED, socialId = SocialId("eishay"), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser)))
      socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Andrew Conner", state = SocialUserInfoStates.CREATED, socialId = SocialId("andrew.conner"), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser)))
      socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Bob User", state = SocialUserInfoStates.FETCHED_USING_FRIEND, socialId = SocialId("bob.user"), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser)))

      // Users that does not need to be processed
      socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Eishay Smith2", state = SocialUserInfoStates.CREATED, socialId = SocialId("eishay2"), networkType = SocialNetworks.FACEBOOK, credentials = None))
      socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Bob User2", state = SocialUserInfoStates.FETCHED_USING_SELF, socialId = SocialId("bob.user2"), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser)))
      socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "Bob User3", state = SocialUserInfoStates.FETCHED_USING_FRIEND, socialId = SocialId("bob.user3"), networkType = SocialNetworks.FACEBOOK, credentials = None))

      user
    }
  }

  "SocialUserInfo" should {

    "serialize properly" in {
      val oAuth2Info = OAuth2Info(accessToken = "AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD",
        tokenType = None, expiresIn = None, refreshToken = None)
      val socialUser = SocialUser(IdentityId("100004067535411", "facebook"), "Boaz", "Tal", "Boaz Tal",
        Some("boaz.tal@gmail.com"), Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None,
        Some(oAuth2Info), None)
      val sui = SocialUserInfo(userId = Option(Id(1)), fullName = "Eishay Smith", state = SocialUserInfoStates.CREATED,
        socialId = SocialId("eishay"), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser))
      val json = Json.toJson(sui)
      val deserialized = Json.fromJson[SocialUserInfo](json).get
      deserialized === sui
    }

    "serialize properly with null lastGraphRefresh" in {
      val oAuth2Info = OAuth2Info(accessToken = "AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD",
        tokenType = None, expiresIn = None, refreshToken = None)
      val socialUser = SocialUser(IdentityId("100004067535411", "facebook"), "Boaz", "Tal", "Boaz Tal",
        Some("boaz.tal@gmail.com"), Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None,
        Some(oAuth2Info), None)
      val sui = SocialUserInfo(userId = Option(Id(1)), fullName = "Eishay Smith", state = SocialUserInfoStates.CREATED,
        socialId = SocialId("eishay"), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser),
        lastGraphRefresh = None)
      val json = Json.toJson(sui)
      val deserialized = Json.fromJson[SocialUserInfo](json).get
      deserialized === sui
    }

    "serialize properly with no lastGraphRefresh" in {
      val oAuth2Info = OAuth2Info(accessToken = "AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD",
        tokenType = None, expiresIn = None, refreshToken = None)
      val socialUser = SocialUser(IdentityId("100004067535411", "facebook"), "Boaz", "Tal", "Boaz Tal",
        Some("boaz.tal@gmail.com"), Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None,
        Some(oAuth2Info), None)
      val sui = SocialUserInfo(userId = Option(Id(1)), fullName = "Eishay Smith", state = SocialUserInfoStates.CREATED,
        socialId = SocialId("eishay"), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser),
        lastGraphRefresh = None)
      val json = Json.toJson(sui).as[JsObject] - "lastGraphRefresh"
      val deserialized = Json.fromJson[SocialUserInfo](json).get
      deserialized === sui
    }

    "get pages" in {
      withDb() { implicit injector =>
        setup()
        val page0 = db.readOnlyMaster { implicit c =>
          socialUserInfoRepo.page(0, 2)
        }
        page0.size === 2
        page0(0).fullName === "Bob User3"
        val page2 = db.readOnlyMaster { implicit c =>
          socialUserInfoRepo.page(2, 2)
        }
        page2.size === 2
        page2(1).fullName === "Eishay Smith"
      }
    }

    "get large page" in {
      withDb() { implicit injector =>
        setup()
        val page0 = db.readOnlyMaster { implicit s =>
          socialUserInfoRepo.page(0, 2000)
        }
        page0.size === 6
      }
    }

    "get larger page" in {
      withDb() { implicit injector =>
        setup()
        val page0 = db.readOnlyMaster { implicit s =>
          socialUserInfoRepo.page(0, 4)
        }
        page0(0).fullName === "Bob User3"
        page0.size === 4
        val page1 = db.readOnlyMaster { implicit s =>
          socialUserInfoRepo.page(1, 4)
        }
        page1.size === 2
      }
    }

    "import friends" in {
      withDb(FakeClockModule()) { implicit injector =>
        val none_unprocessed = db.readOnlyMaster { implicit s =>
          socialUserInfoRepo.getUnprocessed()
        }

        none_unprocessed.size === 0

        setup()
        inject[Clock].asInstanceOf[FakeClock] += Hours.ONE
        val unprocessed = db.readOnlyMaster { implicit s =>
          socialUserInfoRepo.getUnprocessed()
        }

        unprocessed.size === 3
        unprocessed(0).fullName === "Eishay Smith"

      }
    }
  }

}

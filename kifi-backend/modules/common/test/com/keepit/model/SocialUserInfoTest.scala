package com.keepit.model

import org.specs2.mutable._

import com.google.inject.Injector
import com.keepit.akka.{TestAkkaSystem, TestKitScope}
import com.keepit.common.db.{TestSlickSessionProvider, Id}
import com.keepit.common.social.SocialId
import com.keepit.common.social.SocialNetworks
import com.keepit.common.time._
import com.keepit.serializer.SocialUserInfoSerializer
import com.keepit.test._

import play.api.libs.json.JsObject
import securesocial.core._

class SocialUserInfoTest extends Specification with TestDBRunner with TestAkkaSystem {

  def setup()(implicit injector: Injector): User = {
    db.readWrite { implicit s =>
      val oAuth2Info = OAuth2Info(accessToken = "AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD",
                                  tokenType = None, expiresIn = None, refreshToken = None)
      val socialUser = SocialUser(UserId("100004067535411", "facebook"),"Boaz", "Tal", "Boaz Tal",
        Some("boaz.tal@gmail.com"), Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None,
        Some(oAuth2Info), None)

      val user = userRepo.save(User(firstName = "Eishay", lastName = "Smith"))

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
      val socialUser = SocialUser(UserId("100004067535411", "facebook"),"Boaz", "Tal", "Boaz Tal",
        Some("boaz.tal@gmail.com"), Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None,
        Some(oAuth2Info), None)
      val sui = SocialUserInfo(userId = Option(Id(1)), fullName = "Eishay Smith", state = SocialUserInfoStates.CREATED,
        socialId = SocialId("eishay"), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser))
      val json = SocialUserInfoSerializer.socialUserInfoSerializer.writes(sui)
      val deserialized = SocialUserInfoSerializer.socialUserInfoSerializer.reads(json).get
      deserialized === sui
    }

    "serialize properly with null lastGraphRefresh" in {
      val oAuth2Info = OAuth2Info(accessToken = "AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD",
        tokenType = None, expiresIn = None, refreshToken = None)
      val socialUser = SocialUser(UserId("100004067535411", "facebook"),"Boaz", "Tal", "Boaz Tal",
        Some("boaz.tal@gmail.com"), Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None,
        Some(oAuth2Info), None)
      val sui = SocialUserInfo(userId = Option(Id(1)), fullName = "Eishay Smith", state = SocialUserInfoStates.CREATED,
        socialId = SocialId("eishay"), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser),
        lastGraphRefresh = None)
      val json = SocialUserInfoSerializer.socialUserInfoSerializer.writes(sui)
      val deserialized = SocialUserInfoSerializer.socialUserInfoSerializer.reads(json).get
      deserialized === sui
    }

    "serialize properly with no lastGraphRefresh" in {
      val oAuth2Info = OAuth2Info(accessToken = "AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD",
        tokenType = None, expiresIn = None, refreshToken = None)
      val socialUser = SocialUser(UserId("100004067535411", "facebook"),"Boaz", "Tal", "Boaz Tal",
        Some("boaz.tal@gmail.com"), Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None,
        Some(oAuth2Info), None)
      val sui = SocialUserInfo(userId = Option(Id(1)), fullName = "Eishay Smith", state = SocialUserInfoStates.CREATED,
        socialId = SocialId("eishay"), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser),
        lastGraphRefresh = None)
      val json = SocialUserInfoSerializer.socialUserInfoSerializer.writes(sui).as[JsObject] - "lastGraphRefresh"
      val deserialized = SocialUserInfoSerializer.socialUserInfoSerializer.reads(json).get
      deserialized === sui
    }

    "use cache properly" in new TestKitScope {
      withDB() { implicit injector =>
        val user = setup()
        db.readWrite { implicit c =>
          def isInCache = inject[SocialUserInfoRepoImpl].userCache.get(SocialUserInfoUserKey(user.id.get)).isDefined

          val origSocialUser = socialUserInfoRepo.getByUser(user.id.get).head
          awaitCond(isInCache)

          socialUserInfoRepo.save(origSocialUser.copy(fullName = "John Smith"))
          isInCache must beFalse

          val newSocialUser = socialUserInfoRepo.getByUser(user.id.get).head
          awaitCond(isInCache)

          newSocialUser.fullName === "John Smith"
        }
        db.readOnly { implicit s => socialUserInfoRepo.get(SocialId("eishay"), SocialNetworks.FACEBOOK) }
        val socialUserOpt = inject[TestSlickSessionProvider].doWithoutCreatingSessions {
          db.readOnly { implicit s => socialUserInfoRepo.getOpt(SocialId("eishay"), SocialNetworks.FACEBOOK) }
        }
        socialUserOpt.map(_.fullName) must beSome("John Smith")
      }
    }

    "get pages" in {
      withDB() { implicit injector =>
        setup()
        val page0 = db.readOnly { implicit c =>
          socialUserInfoRepo.page(0, 2)
        }
        page0.size === 2
        page0(0).fullName === "Bob User3"
        val page2 = db.readOnly { implicit c =>
          socialUserInfoRepo.page(2, 2)
        }
        page2.size === 2
        page2(1).fullName === "Eishay Smith"
      }
    }

    "get large page" in {
      withDB() { implicit injector =>
        setup()
        val page0 = db.readOnly { implicit s =>
          socialUserInfoRepo.page(0, 2000)
        }
        page0.size === 6
      }
    }

    "get larger page" in {
      withDB() { implicit injector =>
        setup()
        val page0 = db.readOnly { implicit s =>
          socialUserInfoRepo.page(0, 4)
        }
        page0(0).fullName === "Bob User3"
        page0.size === 4
        val page1 = db.readOnly { implicit s =>
          socialUserInfoRepo.page(1, 4)
        }
        page1.size === 2
      }
    }

    "import friends" in {
      withDB() { implicit injector =>

        val none_unprocessed = db.readOnly { implicit s =>
          socialUserInfoRepo.getUnprocessed()
        }

        none_unprocessed.size === 0

        setup()
        val unprocessed = db.readOnly { implicit s =>
          socialUserInfoRepo.getUnprocessed()
        }

        unprocessed.size === 3
        unprocessed(0).fullName === "Eishay Smith"

      }
    }
  }

}

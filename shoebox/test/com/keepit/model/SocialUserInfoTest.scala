package com.keepit.model

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

import com.keepit.common.db.CX
import com.keepit.common.db.CX._
import com.keepit.test.EmptyApplication
import com.keepit.common.social.SocialId
import com.keepit.common.social.SocialNetworks

import securesocial.core._

import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._

@RunWith(classOf[JUnitRunner])
class SocialUserInfoTest extends SpecificationWithJUnit {
  
  def setup() = {
    CX.withConnection { implicit c =>
      val oAuth2Info = OAuth2Info(accessToken = "AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD", 
                                  tokenType = None, expiresIn = None, refreshToken = None)
      val socialUser = SocialUser(UserId("100004067535411", "facebook"), "Boaz Tal", Some("boaz.tal@gmail.com"), 
        Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, true, None, Some(oAuth2Info), None)

      val user = CX.withConnection { implicit c =>
        User(firstName = "Eishay", lastName = "Smith").save
      }
      
      // Users that need to be processed
      SocialUserInfo(userId = user.id, fullName = "Eishay Smith", state = SocialUserInfo.States.CREATED, socialId = SocialId("eishay"), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser)).save
      SocialUserInfo(userId = user.id, fullName = "Andrew Conner", state = SocialUserInfo.States.CREATED, socialId = SocialId("andrew.conner"), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser)).save
      SocialUserInfo(userId = user.id, fullName = "Bob User", state = SocialUserInfo.States.FETCHED_USING_FRIEND, socialId = SocialId("bob.user"), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser)).save
      
      // Users that does not need to be processed
      SocialUserInfo(userId = user.id, fullName = "Eishay Smith2", state = SocialUserInfo.States.CREATED, socialId = SocialId("eishay2"), networkType = SocialNetworks.FACEBOOK, credentials = None).save
      SocialUserInfo(userId = user.id, fullName = "Bob User2", state = SocialUserInfo.States.FETCHED_USING_SELF, socialId = SocialId("bob.user2"), networkType = SocialNetworks.FACEBOOK, credentials = Some(socialUser)).save
      SocialUserInfo(userId = user.id, fullName = "Bob User3", state = SocialUserInfo.States.FETCHED_USING_FRIEND, socialId = SocialId("bob.user3"), networkType = SocialNetworks.FACEBOOK, credentials = None).save

    }
  }

  "SocialUserInfo" should {
    "import friends" in {
      running(new EmptyApplication()) {
        
        val none_unprocessed = CX.withConnection { implicit c =>
          SocialUserInfo.getUnprocessed()
        }
        
        none_unprocessed.size === 0
        
        setup()
        val unprocessed = CX.withConnection { implicit c =>
          SocialUserInfo.getUnprocessed()
        }
        
        unprocessed.size === 3
        unprocessed(0).fullName === "Eishay Smith"
        
      }
    }
  }
  
}

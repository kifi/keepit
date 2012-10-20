package com.keepit.social

import play.api.Application
import securesocial.core.{UserServicePlugin, UserId, SocialUser}
import com.keepit.common.db.CX
import com.keepit.common.db._
import com.keepit.common.net.HttpClient
import com.keepit.model._
import com.keepit.inject._
import play.api.Play.current
import com.keepit.common.logging.Logging
import com.keepit.common.social.{SocialId, SocialNetworks, SocialNetworkType}

class SecureSocialUserService(application: Application) extends UserServicePlugin(application) with Logging {

  /**
   * Assuming for now that there is only facebook
   */
  def find(id: UserId): Option[SocialUser] = 
    CX.withConnection { implicit conn =>
      SocialUserInfo.getOpt(SocialId(id.id), SocialNetworks.FACEBOOK)
    } match {
      case None => None
      case Some(user) => user.credentials
    }
  

  def save(socialUser: SocialUser): Unit = 
    CX.withConnection { implicit conn => {
      //todo(eishay) take the network type from the socialUser
      val socialUserInfo = internUser(SocialId(socialUser.id.id), SocialNetworks.FACEBOOK, socialUser)
      socialUserInfo.withCredentials(socialUser).save 
    }    
  }
  
  private def createUser(displayName: String) = {
    val nameParts = displayName.split(' ')
    User(firstName = nameParts(0),
        lastName = nameParts.tail.mkString(" ")
    )    
  }

  private def internUser(socialId: SocialId, socialNetworkType: SocialNetworkType, socialUser: SocialUser): SocialUserInfo = CX.withConnection { implicit conn =>
    SocialUserInfo.getOpt(socialId, socialNetworkType) match {
      case Some(socialUserInfo) if (!socialUserInfo.userId.isEmpty) => socialUserInfo
      case Some(socialUserInfo) if (socialUserInfo.userId.isEmpty) =>
        val user = createUser(socialUserInfo.fullName).save
        //social user info with user must be FETCHED_USING_SELF, so setting user should trigger a pull
        //todo(eishay): send a direct fetch request 
        socialUserInfo.withUser(user).save
      case None =>
        val user = createUser(socialUser.displayName).save
        SocialUserInfo(userId = user.id, socialId = socialId, networkType = SocialNetworks.FACEBOOK, fullName = socialUser.displayName, credentials = Some(socialUser)).save
    }
  }
  
}

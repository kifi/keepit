package com.keepit.social

import play.api.Application
import securesocial.core.{UserServicePlugin, UserId, SocialUser}
import com.keepit.common.db.CX
import com.keepit.common.db._
import com.keepit.common.social.SocialGraphPlugin
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
      case None =>
        log.debug("No SocialUserInfo found for %s".format(id))
        None
      case Some(user) => 
        log.debug("User found: %s for %s".format(user, id))
        user.credentials
    }

  def save(socialUser: SocialUser): Unit = CX.withConnection { implicit conn => 
    //todo(eishay) take the network type from the socialUser
    log.debug("persisting social user %s".format(socialUser))
    val socialUserInfo = internUser(SocialId(socialUser.id.id), SocialNetworks.FACEBOOK, socialUser)
    require(socialUserInfo.credentials.isDefined, "social user info's credentias is not defined: %s".format(socialUserInfo))
    require(socialUserInfo.userId.isDefined, "social user id  is not defined: %s".format(socialUserInfo))
    if (socialUserInfo.state != SocialUserInfo.States.FETCHED_USING_SELF) {
      inject[SocialGraphPlugin].asyncFetch(socialUserInfo)
    }
    log.debug("persisting %s into %s".format(socialUser, socialUserInfo))
  }
  
  private def createUser(displayName: String) = {
    log.debug("creating new user for %s".format(displayName))
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
        socialUserInfo.withUser(user).withCredentials(socialUser).save
      case None =>
        val user = createUser(socialUser.displayName).save
        log.debug("creating new SocialUserInfo for %s".format(user))
        val userInfo = SocialUserInfo(userId = Some(user.id.get),//verify saved 
            socialId = socialId, networkType = SocialNetworks.FACEBOOK, 
            fullName = socialUser.displayName, credentials = Some(socialUser)).save
        log.debug("SocialUserInfo created is %s".format(userInfo))
        userInfo
    }
  }
  
}

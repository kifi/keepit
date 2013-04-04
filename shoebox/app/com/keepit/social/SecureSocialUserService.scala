package com.keepit.social

import play.api.Application
import securesocial.core.{UserServicePlugin, UserId, SocialUser, UserService}
import com.keepit.common.db.slick._
import com.keepit.common.db._
import com.keepit.common.social.SocialGraphPlugin
import com.keepit.common.net.HttpClient
import com.keepit.model._
import com.keepit.inject._
import com.keepit.common.logging.Logging
import com.keepit.common.social.{SocialId, SocialNetworks, SocialNetworkType}
import com.google.inject.{Inject, Singleton}

class SecureSocialUserService(implicit val application: Application) extends UserServicePlugin(application) {
  lazy val proxy = inject[SecureSocialUserPlugin]
  def find(id: UserId): Option[SocialUser] = proxy.find(id)
  def save(socialUser: SocialUser): Unit = proxy.save(socialUser)
}

@Singleton
class SecureSocialUserPlugin @Inject() (
    db: Database,
    socialUserInfoRepo: SocialUserInfoRepo,
    socialGraphPlugin: SocialGraphPlugin,
    userRepo: UserRepo)
  extends UserService with Logging {

  /**
   * Assuming for now that there is only facebook
   */
  def find(id: UserId): Option[SocialUser] =
    db.readOnly { implicit s =>
      socialUserInfoRepo.getOpt(SocialId(id.id), SocialNetworks.FACEBOOK)
    } match {
      case None =>
        log.debug("No SocialUserInfo found for %s".format(id))
        None
      case Some(user) =>
        log.debug("User found: %s for %s".format(user, id))
        Some(user.credentials.getOrElse(throw new Exception("user [%s] does not have credentials".format(user))))
    }

  def save(socialUser: SocialUser): Unit = db.readWrite { implicit s =>
    //todo(eishay) take the network type from the socialUser
    log.debug("persisting social user %s".format(socialUser))
    val socialUserInfo = socialUserInfoRepo.save(internUser(SocialId(socialUser.id.id), SocialNetworks.FACEBOOK, socialUser)
                           .withCredentials(socialUser))
    require(socialUserInfo.credentials.isDefined, "social user info's credentias is not defined: %s".format(socialUserInfo))
    require(socialUserInfo.userId.isDefined, "social user id  is not defined: %s".format(socialUserInfo))
    if (socialUserInfo.state != SocialUserInfoStates.FETCHED_USING_SELF) {
      socialGraphPlugin.asyncFetch(socialUserInfo)
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

  private def internUser(socialId: SocialId, socialNetworkType: SocialNetworkType, socialUser: SocialUser): SocialUserInfo = db.readWrite { implicit s =>
    socialUserInfoRepo.getOpt(socialId, socialNetworkType) match {
      case Some(socialUserInfo) if (!socialUserInfo.userId.isEmpty) =>
        socialUserInfo
      case Some(socialUserInfo) if (socialUserInfo.userId.isEmpty) =>
        val user = userRepo.save(createUser(socialUserInfo.fullName))
        //social user info with user must be FETCHED_USING_SELF, so setting user should trigger a pull
        //todo(eishay): send a direct fetch request
        socialUserInfo.withUser(user)
      case None =>
        val user = userRepo.save(createUser(socialUser.displayName))
        log.debug("creating new SocialUserInfo for %s".format(user))
        val userInfo = SocialUserInfo(userId = Some(user.id.get),//verify saved
            socialId = socialId, networkType = SocialNetworks.FACEBOOK,
            fullName = socialUser.displayName, credentials = Some(socialUser))
        log.debug("SocialUserInfo created is %s".format(userInfo))
        userInfo
    }
  }
}

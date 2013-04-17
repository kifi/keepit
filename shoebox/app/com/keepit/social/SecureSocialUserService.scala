package com.keepit.social

import com.google.inject.{Inject, Singleton}
import com.keepit.FortyTwoGlobal
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.social.SocialGraphPlugin
import com.keepit.common.social.{SocialId, SocialNetworks, SocialNetworkType}
import com.keepit.inject._
import com.keepit.model._

import play.api.Application
import play.api.Play
import play.api.Play.current
import securesocial.core.providers.Token
import securesocial.core.{UserServicePlugin, UserId, SocialUser, Identity, UserService}

class SecureSocialUserService(implicit val application: Application) extends UserServicePlugin(application) {
  lazy val global = application.global.asInstanceOf[FortyTwoGlobal]
  def proxy: Option[SecureSocialUserPlugin] = {
    // Play will try to initialize this plugin before FortyTwoGlobal is fully initialized. This will cause
    // FortyTwoGlobal to attempt to initialize AppScope in multiple threads, causing deadlock. This allows us to wait
    // until the injector is initialized to do something if we want. When we need the plugin to be instantiated,
    // we can fail with None.get which will let us know immediately that there is a problem.
    if (global.initialized) Some(new RichInjector(global.injector).inject[SecureSocialUserPlugin]) else None
  }
  def find(id: UserId): Option[SocialUser] = proxy.get.find(id)
  def save(user: Identity): SocialUser = proxy.get.save(user)

  def findByEmailAndProvider(email: String, providerId: String): Option[SocialUser] =
    proxy.get.findByEmailAndProvider(email, providerId)
  def save(token: Token) { proxy.get.save(token) }
  def findToken(token: String) = proxy.get.findToken(token)
  def deleteToken(uuid: String) { proxy.get.deleteToken(uuid) }
  def deleteExpiredTokens() { proxy.foreach(_.deleteExpiredTokens()) }
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
        user.credentials
    }

  def save(identity: Identity): SocialUser = db.readWrite { implicit s =>
    val socialUser = SocialUser(identity)
    //todo(eishay) take the network type from the socialUser
    log.debug("persisting social user %s".format(socialUser))
    val socialUserInfo = socialUserInfoRepo.save(
      internUser(SocialId(socialUser.id.id), SocialNetworks.FACEBOOK, socialUser).withCredentials(socialUser))
    require(socialUserInfo.credentials.isDefined, "social user info's credentias is not defined: %s".format(socialUserInfo))
    require(socialUserInfo.userId.isDefined, "social user id  is not defined: %s".format(socialUserInfo))
    if (socialUserInfo.state != SocialUserInfoStates.FETCHED_USING_SELF) {
      socialGraphPlugin.asyncFetch(socialUserInfo)
    }
    log.debug("persisting %s into %s".format(socialUser, socialUserInfo))

    socialUser
  }

  private def createUser(displayName: String): User = {
    log.debug("creating new user for %s".format(displayName))
    val nameParts = displayName.split(' ')
    User(firstName = nameParts(0),
        lastName = nameParts.tail.mkString(" "),
        state = if(Play.isDev) UserStates.ACTIVE else UserStates.PENDING
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
        val user = userRepo.save(createUser(socialUser.fullName))
        log.debug("creating new SocialUserInfo for %s".format(user))
        val userInfo = SocialUserInfo(userId = Some(user.id.get),//verify saved
            socialId = socialId, networkType = SocialNetworks.FACEBOOK,
            fullName = socialUser.fullName, credentials = Some(socialUser))
        log.debug("SocialUserInfo created is %s".format(userInfo))
        userInfo
    }
  }

  // TODO(greg): implement when we start using the UsernamePasswordProvider
  def findByEmailAndProvider(email: String, providerId: String): Option[SocialUser] = ???
  def save(token: Token) {}
  def findToken(token: String): Option[Token] = None
  def deleteToken(uuid: String) {}
  def deleteExpiredTokens() {}
}

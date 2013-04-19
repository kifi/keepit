package com.keepit.social

import com.google.inject.{Inject, Singleton}
import com.keepit.FortyTwoGlobal
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.social.SocialGraphPlugin
import com.keepit.common.social.SocialId
import com.keepit.common.social.SocialNetworkType
import com.keepit.inject._
import com.keepit.model._
import play.api.Application
import play.api.Play
import play.api.Play.current
import securesocial.core._
import securesocial.core.providers.Token

class SecureSocialIdGenerator(app: Application) extends IdGenerator(app) {
  def generate: String = ExternalId[String]().toString
}

class SecureSocialAuthenticatorStore(app: Application) extends AuthenticatorStore(app) {
  lazy val global = app.global.asInstanceOf[FortyTwoGlobal]
  def proxy: Option[SecureSocialAuthenticatorPlugin] = {
    if (global.initialized) {
      val injector = new RichInjector(global.injector)
      Some(new SecureSocialAuthenticatorPlugin(
        injector.inject[Database], injector.inject[SocialUserInfoRepo], injector.inject[UserSessionRepo], app))
    } else None
  }
  def save(authenticator: Authenticator): Either[Error, Unit] = proxy.get.save(authenticator)
  def find(id: String): Either[Error, Option[Authenticator]] = proxy.get.find(id)
  def delete(id: String): Either[Error, Unit] = proxy.get.delete(id)
}

@AppScoped
class SecureSocialAuthenticatorPlugin @Inject()(
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  sessionRepo: UserSessionRepo,
  app: Application) extends AuthenticatorStore(app) {
  private def sessionFromAuthenticator(authenticator: Authenticator): UserSession = {
    val (socialId, provider) = (SocialId(authenticator.userId.id), SocialNetworkType(authenticator.userId.providerId))
    val userId = db.readOnly { implicit s => socialUserInfoRepo.get(socialId, provider).userId }
    UserSession(
      userId = userId,
      externalId = ExternalId[UserSession](authenticator.id),
      socialId = socialId,
      provider = provider,
      expires = authenticator.expirationDate,
      state = if (authenticator.isValid) UserSessionStates.ACTIVE else UserSessionStates.INACTIVE
    )
  }
  private def authenticatorFromSession(session: UserSession): Authenticator = Authenticator(
    id = session.externalId.id,
    userId = UserId(session.socialId.id, session.provider.name),
    creationDate = session.createdAt,
    lastUsed = session.updatedAt,
    expirationDate = session.expires
  )

  def save(authenticator: Authenticator): Either[Error, Unit] = {
    val session = db.readWrite { implicit s =>
      val newSession = sessionFromAuthenticator(authenticator)
      val maybeOldSession = sessionRepo.getOpt(newSession.externalId)
      sessionRepo.save(newSession.copy(
        id = maybeOldSession.map(_.id.get),
        createdAt = maybeOldSession.map(_.createdAt).getOrElse(newSession.createdAt)
      ))
    }
    Right(authenticatorFromSession(session))
  }
  def find(id: String): Either[Error, Option[Authenticator]] = Right {
    db.readOnly { implicit s =>
      sessionRepo.getOpt(ExternalId[UserSession](id))
    }.collect {
      case s if s.isValid => authenticatorFromSession(s)
    }
  }
  def delete(id: String): Either[Error, Unit] = Right {
    db.readWrite { implicit s =>
      sessionRepo.getOpt(ExternalId[UserSession](id)).foreach { session =>
        sessionRepo.save(session invalidated)
      }
    }
  }
}

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

  def find(id: UserId): Option[SocialUser] =
    db.readOnly { implicit s =>
      socialUserInfoRepo.getOpt(SocialId(id.id), SocialNetworkType(id.providerId))
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
    val socialUserInfo = socialUserInfoRepo.save(internUser(
      SocialId(socialUser.id.id), SocialNetworkType(socialUser.id.providerId), socialUser).withCredentials(socialUser))
    require(socialUserInfo.credentials.isDefined,
      "social user info's credentials is not defined: %s".format(socialUserInfo))
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
        state = if(Play.isDev) UserStates.PENDING else UserStates.PENDING
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
            socialId = socialId, networkType = socialNetworkType,
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

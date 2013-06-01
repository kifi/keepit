package com.keepit.social

import com.google.inject.{Inject, Singleton}
import com.keepit.FortyTwoGlobal
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck._
import com.keepit.common.logging.Logging
import com.keepit.common.social.{SocialGraphPlugin, SocialId, SocialNetworkType}
import com.keepit.common.store.S3ImageStore
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
  lazy val plugin = {
    val injector = new RichInjector(global.injector)
    new SecureSocialAuthenticatorPlugin(
      injector.inject[Database],
      injector.inject[SocialUserInfoRepo],
      injector.inject[UserSessionRepo],
      injector.inject[HealthcheckPlugin],
      app)
  }
  def proxy: Option[SecureSocialAuthenticatorPlugin] = {
    if (global.initialized) Some(plugin) else None
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
  healthcheckPlugin: HealthcheckPlugin,
  app: Application) extends AuthenticatorStore(app) {

  private def reportExceptions[T](f: => T): Either[Error, T] =
    try Right(f) catch { case ex: Throwable =>
      healthcheckPlugin.addError(
        HealthcheckError(error = Some(ex), method = None, path = None, callType = Healthcheck.INTERNAL))
      Left(new Error(ex))
    }

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

  private def needsUpdate(oldSession: UserSession, newSession: UserSession): Boolean = {
    // We only want to save if we actually changed something. SecureSocial likes to "touch" the session to update the
    // last used time, but we're not using that right now. If we eventually do want to keep track of the last used
    // time, we should try to avoid writing to the database every time.
    oldSession.copy(
      updatedAt = newSession.updatedAt,
      createdAt = newSession.createdAt,
      id = newSession.id) != newSession
  }

  def save(authenticator: Authenticator): Either[Error, Unit] = reportExceptions {
    val newSession = sessionFromAuthenticator(authenticator)
    authenticatorFromSession {
      val oldSessionOpt = db.readOnly { implicit s => sessionRepo.getOpt(newSession.externalId) }
      if (oldSessionOpt.exists(!needsUpdate(_, newSession))) {
        oldSessionOpt.get
      } else {
        db.readWrite { implicit s =>
          sessionRepo.save(newSession.copy(
            id = oldSessionOpt.map(_.id.get),
            createdAt = oldSessionOpt.map(_.createdAt).getOrElse(newSession.createdAt)
          ))
        }
      }
    }
  }
  def find(id: String): Either[Error, Option[Authenticator]] = reportExceptions {
    val externalIdOpt = try {
      Some(ExternalId[UserSession](id))
    } catch {
      case ex: Throwable => None
    }

    externalIdOpt.map{ externalId =>
      db.readOnly { implicit s =>
        sessionRepo.getOpt(externalId)
      } collect {
        case s if s.isValid => authenticatorFromSession(s)
      }
    } flatten
  }
  def delete(id: String): Either[Error, Unit] = reportExceptions {
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
  def save(token: Token) = proxy.get.save(token)
  def findToken(token: String) = proxy.get.findToken(token)
  def deleteToken(uuid: String) = proxy.get.deleteToken(uuid)
  def deleteExpiredTokens() {
    // Even if global is defined, getting the SecureSocialUserPlugin seems to cause deadlocks on start.
    // Fortunately our implementation of this method does nothing so it doesn't matter.
  }

}

@Singleton
class SecureSocialUserPlugin @Inject() (
    db: Database,
    socialUserInfoRepo: SocialUserInfoRepo,
    userRepo: UserRepo,
    imageStore: S3ImageStore,
    healthcheckPlugin: HealthcheckPlugin)
  extends UserService with Logging {

  private def reportExceptions[T](f: => T): T =
    try f catch { case ex: Throwable =>
      healthcheckPlugin.addError(
        HealthcheckError(error = Some(ex), method = None, path = None, callType = Healthcheck.INTERNAL))
      throw ex
    }

  private var maybeSocialGraphPlugin: Option[SocialGraphPlugin] = None

  @Inject(optional = true)
  def setSocialGraphPlugin(sgp: SocialGraphPlugin) {
    maybeSocialGraphPlugin = Some(sgp)
  }

  def find(id: UserId): Option[SocialUser] = reportExceptions {
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
  }

  def save(identity: Identity): SocialUser = reportExceptions {
    db.readWrite { implicit s =>
      val socialUser = SocialUser(identity)
      log.debug("persisting social user %s".format(socialUser))
      val socialUserInfo = internUser(
        SocialId(socialUser.id.id), SocialNetworkType(socialUser.id.providerId), socialUser).withCredentials(socialUser)
      require(socialUserInfo.credentials.isDefined,
        "social user info's credentials is not defined: %s".format(socialUserInfo))
      require(socialUserInfo.userId.isDefined, "social user id  is not defined: %s".format(socialUserInfo))
      for (sgp <- maybeSocialGraphPlugin if socialUserInfo.state != SocialUserInfoStates.FETCHED_USING_SELF) {
        sgp.asyncFetch(socialUserInfo)
      }
      log.debug("persisting %s into %s".format(socialUser, socialUserInfo))
      socialUser
    }
  }

  private def createUser(displayName: String): User = {
    log.debug("creating new user for %s".format(displayName))
    val nameParts = displayName.split(' ')
    User(firstName = nameParts(0),
        lastName = nameParts.tail.mkString(" "),
        state = if(Play.isDev) UserStates.ACTIVE else UserStates.PENDING
    )
  }

  private def internUser(socialId: SocialId, socialNetworkType: SocialNetworkType,
      socialUser: SocialUser)(implicit session: RWSession): SocialUserInfo = {
    socialUserInfoRepo.getOpt(socialId, socialNetworkType) match {
      case Some(socialUserInfo) if (!socialUserInfo.userId.isEmpty) =>
        socialUserInfo
      case Some(socialUserInfo) if (socialUserInfo.userId.isEmpty) =>
        val user = userRepo.save(createUser(socialUserInfo.fullName))

        //social user info with user must be FETCHED_USING_SELF, so setting user should trigger a pull
        //todo(eishay): send a direct fetch request
        val sui = socialUserInfoRepo.save(socialUserInfo.withUser(user))
        imageStore.updatePicture(sui, user.externalId)
        sui
      case None =>
        val user = userRepo.save(createUser(socialUser.fullName))
        log.debug("creating new SocialUserInfo for %s".format(user))
        val userInfo = SocialUserInfo(userId = Some(user.id.get),//verify saved
            socialId = socialId, networkType = socialNetworkType,
            fullName = socialUser.fullName, credentials = Some(socialUser))
        log.debug("SocialUserInfo created is %s".format(userInfo))

        val sui = socialUserInfoRepo.save(userInfo)
        imageStore.updatePicture(sui, user.externalId)
        sui
    }
  }

  // TODO(greg): implement when we start using the UsernamePasswordProvider
  def findByEmailAndProvider(email: String, providerId: String): Option[SocialUser] = ???
  def save(token: Token) {}
  def findToken(token: String): Option[Token] = None
  def deleteToken(uuid: String) {}
  def deleteExpiredTokens() {}
}

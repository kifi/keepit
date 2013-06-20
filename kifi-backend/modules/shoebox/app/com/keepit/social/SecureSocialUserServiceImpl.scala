package com.keepit.social

import com.google.inject.{Inject, Singleton}
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.common.store.S3ImageStore
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin}
import securesocial.core._
import com.keepit.common.logging.Logging
import com.keepit.common.social.{SocialGraphPlugin, SocialNetworkType, SocialId}
import com.keepit.common.db.{ExternalId, Id}
import play.api.{Application, Play}
import Play.current
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.inject.AppScoped
import securesocial.core.providers.Token
import scala.Some
import com.keepit.model.User
import com.keepit.common.healthcheck.HealthcheckError
import securesocial.core.UserId
import com.keepit.model.SocialUserInfo

@Singleton
class SecureSocialUserPluginImpl @Inject() (
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  userRepo: UserRepo,
  imageStore: S3ImageStore,
  healthcheckPlugin: HealthcheckPlugin,
  userExperimentRepo: UserExperimentRepo,
  emailRepo: EmailAddressRepo,
  socialGraphPlugin: SocialGraphPlugin)
  extends UserService with SecureSocialUserPlugin with Logging {

  private def reportExceptions[T](f: => T): T =
    try f catch { case ex: Throwable =>
      healthcheckPlugin.addError(
        HealthcheckError(error = Some(ex), method = None, path = None, callType = Healthcheck.INTERNAL))
      throw ex
    }

  def find(id: UserId): Option[SocialUser] = reportExceptions {
    db.readOnly { implicit s =>
      socialUserInfoRepo.getOpt(SocialId(id.id), SocialNetworkType(id.providerId))
    } match {
      case None =>
        log.info("No SocialUserInfo found for %s".format(id))
        None
      case Some(user) =>
        log.info("User found: %s for %s".format(user, id))
        user.credentials
    }
  }

  def save(identity: Identity): SocialUser = reportExceptions {
    db.readWrite { implicit s =>
      val (userId, socialUser) = getUserIdAndSocialUser(identity)
      log.info("persisting social user %s".format(socialUser))
      val socialUserInfo = internUser(
        SocialId(socialUser.id.id), SocialNetworkType(socialUser.id.providerId), socialUser, userId)
      require(socialUserInfo.credentials.isDefined,
        "social user info's credentials is not defined: %s".format(socialUserInfo))
      require(socialUserInfo.userId.isDefined, "social user id  is not defined: %s".format(socialUserInfo))
      socialGraphPlugin.asyncFetch(socialUserInfo)
      log.info("persisting %s into %s".format(socialUser, socialUserInfo))
      socialUser
    }
  }

  private def getUserIdAndSocialUser(identity: Identity): (Option[Id[User]], SocialUser) = identity match {
    case UserIdentity(userId, socialUser) => (userId, socialUser)
    case ident => (None, SocialUser(ident))
  }

  private def createUser(identity: Identity): User = {
    log.info(s"Creating new user for ${identity.fullName}")
    User(
      firstName = identity.firstName,
      lastName = identity.lastName,
      state = if(Play.isDev) UserStates.ACTIVE else UserStates.PENDING
    )
  }

  private def internUser(socialId: SocialId, socialNetworkType: SocialNetworkType,
                         socialUser: SocialUser, userId: Option[Id[User]])(implicit session: RWSession): SocialUserInfo = {
    val suiOpt = socialUserInfoRepo.getOpt(socialId, socialNetworkType)
    val userOpt = userId orElse {
      // TODO: better way of dealing with emails that already exist; for now just link accounts
      socialUser.email flatMap emailRepo.getByAddressOpt map (_.userId)
    } flatMap userRepo.getOpt

    suiOpt.map(_.withCredentials(socialUser)) match {
      case Some(socialUserInfo) if !socialUserInfo.userId.isEmpty =>
        // TODO(greg): handle case where user id in socialUserInfo is different from the one in the session
        if (suiOpt == Some(socialUserInfo)) socialUserInfo else socialUserInfoRepo.save(socialUserInfo)
      case Some(socialUserInfo) if socialUserInfo.userId.isEmpty =>
        val user = userOpt getOrElse userRepo.save(createUser(socialUser))

        //social user info with user must be FETCHED_USING_SELF, so setting user should trigger a pull
        //todo(eishay): send a direct fetch request
        val sui = socialUserInfoRepo.save(socialUserInfo.withUser(user))
        if (userOpt.isEmpty) imageStore.updatePicture(sui, user.externalId)
        sui
      case None =>
        val user = userOpt getOrElse userRepo.save(createUser(socialUser))
        log.info("creating new SocialUserInfo for %s".format(user))
        val userInfo = SocialUserInfo(userId = Some(user.id.get),//verify saved
          socialId = socialId, networkType = socialNetworkType, pictureUrl = socialUser.avatarUrl,
          fullName = socialUser.fullName, credentials = Some(socialUser))
        log.info("SocialUserInfo created is %s".format(userInfo))

        val sui = socialUserInfoRepo.save(userInfo)
        if (userOpt.isEmpty) imageStore.updatePicture(sui, user.externalId)
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

@AppScoped
class SecureSocialAuthenticatorPluginImpl @Inject()(
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  sessionRepo: UserSessionRepo,
  healthcheckPlugin: HealthcheckPlugin,
  app: Application)
  extends AuthenticatorStore(app) with SecureSocialAuthenticatorPlugin  {

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

    externalIdOpt flatMap { externalId =>
      db.readOnly { implicit s =>
        sessionRepo.getOpt(externalId)
      } collect {
        case s if s.isValid => authenticatorFromSession(s)
      }
    }
  }
  def delete(id: String): Either[Error, Unit] = reportExceptions {
    db.readWrite { implicit s =>
      sessionRepo.getOpt(ExternalId[UserSession](id)).foreach { session =>
        sessionRepo.save(session invalidated)
      }
    }
  }
}


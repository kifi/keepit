package com.keepit.social

import com.keepit.inject.AppScoped
import com.google.inject.{Singleton, Inject}
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin}
import com.keepit.common.akka.MonitoredAwait
import play.api.Application
import securesocial.core._
import com.keepit.model.{UserSessionStates, UserSession}
import com.keepit.common.social.{SocialGraphPlugin, SocialNetworkType, SocialId}
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import scala.Some
import securesocial.core.UserId
import com.keepit.common.healthcheck.HealthcheckError
import securesocial.core.providers.Token
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

@AppScoped
class RemoteSecureSocialAuthenticatorPlugin @Inject()(
                                                       shoeboxClient: ShoeboxServiceClient,
                                                       healthcheckPlugin: HealthcheckPlugin,
                                                       monitoredAwait: MonitoredAwait,
                                                       app: Application) extends AuthenticatorStore(app) with SecureSocialAuthenticatorPlugin {

  private def reportExceptions[T](f: => T): Either[Error, T] =
    try Right(f) catch { case ex: Throwable =>
      healthcheckPlugin.addError(
        HealthcheckError(error = Some(ex), method = None, path = None, callType = Healthcheck.INTERNAL))
      Left(new Error(ex))
    }

  private def sessionFromAuthenticator(authenticator: Authenticator): UserSession = {
    val (socialId, provider) = (SocialId(authenticator.userId.id), SocialNetworkType(authenticator.userId.providerId))
    val userIdFuture = shoeboxClient.getSocialUserInfoByNetworkAndSocialId(socialId, provider).map(_.map(_.userId))
    val userId = monitoredAwait.result(userIdFuture, 3 seconds, s"get userid for $socialId and $provider").flatten
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

  def save(authenticator: Authenticator): Either[Error, Unit] = reportExceptions { }
  def find(id: String): Either[Error, Option[Authenticator]] = reportExceptions {
    val externalIdOpt = try {
      Some(ExternalId[UserSession](id))
    } catch {
      case ex: Throwable => None
    }

    externalIdOpt.map{ externalId =>
      val result = monitoredAwait.result(shoeboxClient.getSessionByExternalId(externalId), 3 seconds, s"get session for $externalId")
      result collect {
        case s if s.isValid => authenticatorFromSession(s)
      }
    } flatten
  }
  def delete(id: String): Either[Error, Unit] = reportExceptions { }
}

@Singleton
class RemoteSecureSocialUserPlugin @Inject() (
                                               healthcheckPlugin: HealthcheckPlugin,
                                               shoeboxClient: ShoeboxServiceClient,
                                               monitoredAwait: MonitoredAwait)
  extends UserService with SecureSocialUserPlugin with Logging {

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
    val resFuture = shoeboxClient.getSocialUserInfoByNetworkAndSocialId(SocialId(id.id), SocialNetworkType(id.providerId))
    monitoredAwait.result(resFuture, 3 seconds, s"get user for social user ${id.id} on $id.providerId") match {
      case None =>
        log.info("No SocialUserInfo found for %s".format(id))
        None
      case Some(user) =>
        log.info("User found: %s for %s".format(user, id))
        user.credentials
    }
  }

  def save(identity: Identity): SocialUser = reportExceptions {
    SocialUser(identity)
  }

  // TODO(greg): implement when we start using the UsernamePasswordProvider
  def findByEmailAndProvider(email: String, providerId: String): Option[SocialUser] = ???
  def save(token: Token) {}
  def findToken(token: String): Option[Token] = None
  def deleteToken(uuid: String) {}
  def deleteExpiredTokens() {}
}

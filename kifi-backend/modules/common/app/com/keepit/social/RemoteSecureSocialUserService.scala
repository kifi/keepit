package com.keepit.social

import com.keepit.inject.AppScoped
import com.google.inject.{ Provides, Singleton, Inject }
import com.keepit.model.view.UserSessionView
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.akka.MonitoredAwait
import com.keepit.shoebox.model.ids.UserSessionExternalId
import play.api.Application
import securesocial.core._
import com.keepit.common.logging.Logging
import securesocial.core.IdentityId
import securesocial.core.providers.Token
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import securesocial.controllers.{ TemplatesPlugin, DefaultTemplatesPlugin }

@AppScoped
class RemoteSecureSocialAuthenticatorPlugin @Inject() (
    shoeboxClient: ShoeboxServiceClient,
    airbrake: AirbrakeNotifier,
    monitoredAwait: MonitoredAwait,
    app: Application) extends AuthenticatorStore(app) with SecureSocialAuthenticatorPlugin {

  private def reportExceptions[T](f: => T): Either[Error, T] =
    try Right(f) catch {
      case ex: Throwable =>
        airbrake.notify(ex)
        Left(new Error(ex))
    }

  private def authenticatorFromSession(session: UserSessionView, externalId: UserSessionExternalId): Authenticator = Authenticator(
    id = externalId.id,
    identityId = IdentityId(session.socialId.id, session.provider.name),
    creationDate = session.createdAt,
    lastUsed = session.updatedAt,
    expirationDate = session.expires
  )

  def save(authenticator: Authenticator): Either[Error, Unit] = reportExceptions {}

  def find(id: String): Either[Error, Option[Authenticator]] = reportExceptions {
    val externalIdOpt = try {
      Some(UserSessionExternalId(id))
    } catch {
      case ex: Throwable => None
    }

    externalIdOpt flatMap { externalId =>
      val result = monitoredAwait.result(shoeboxClient.getSessionByExternalId(externalId), 3 seconds, s"get session for $externalId")
      result collect {
        case s if s.valid => authenticatorFromSession(s, externalId)
      }
    }
  }
  def delete(id: String): Either[Error, Unit] = reportExceptions {}
}

@Singleton
class RemoteSecureSocialUserPlugin @Inject() (
    airbrake: AirbrakeNotifier,
    shoeboxClient: ShoeboxServiceClient,
    monitoredAwait: MonitoredAwait) extends UserService with SecureSocialUserPlugin with Logging {

  private def reportExceptions[T](f: => T): T =
    try f catch {
      case ex: Throwable =>
        airbrake.notify(ex)
        throw ex
    }

  private var maybeSocialGraphPlugin: Option[SocialGraphPlugin] = None

  @Inject(optional = true)
  def setSocialGraphPlugin(sgp: SocialGraphPlugin) {
    maybeSocialGraphPlugin = Some(sgp)
  }

  def find(id: IdentityId): Option[UserIdentity] = reportExceptions {
    val resFuture = shoeboxClient.getSocialUserInfoByNetworkAndSocialId(SocialId(id.userId), SocialNetworkType(id.providerId))
    monitoredAwait.result(resFuture, 3 seconds, s"get user for social user ${id.userId} on $id.providerId") match {
      case None =>
        log.info("No SocialUserInfo found for %s".format(id))
        None
      case Some(user) =>
        log.info("User found: %s for %s".format(user, id))
        user.credentials map { UserIdentity(user.userId, _) }
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

case class RemoteSecureSocialModule() extends SecureSocialModule {
  def configure() {
  }

  @Singleton
  @Provides
  def secureSocialAuthenticatorPlugin(
    shoeboxClient: ShoeboxServiceClient,
    airbrake: AirbrakeNotifier,
    monitoredAwait: MonitoredAwait,
    app: play.api.Application): SecureSocialAuthenticatorPlugin = {
    new RemoteSecureSocialAuthenticatorPlugin(shoeboxClient, airbrake, monitoredAwait, app)
  }

  @Singleton
  @Provides
  def secureSocialUserPlugin(
    airbrake: AirbrakeNotifier,
    shoeboxClient: ShoeboxServiceClient,
    monitoredAwait: MonitoredAwait): SecureSocialUserPlugin = {
    new RemoteSecureSocialUserPlugin(airbrake, shoeboxClient, monitoredAwait)
  }

  @Singleton
  @Provides
  def templatesPlugin(app: play.api.Application): TemplatesPlugin = {
    new DefaultTemplatesPlugin(app)
  }
}

package com.keepit.social

import com.keepit.inject.AppScoped
import com.google.inject.{Provides, Singleton, Inject}
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin}
import com.keepit.common.akka.MonitoredAwait
import play.api.Application
import securesocial.core._
import com.keepit.model.{UserSessionStates, UserSession}
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import scala.Some
import securesocial.core.IdentityId
import com.keepit.common.healthcheck.HealthcheckError
import securesocial.core.providers.Token
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import com.keepit.common.controller.{RemoteActionAuthenticator, ActionAuthenticator}
import securesocial.controllers.{TemplatesPlugin, DefaultTemplatesPlugin}

@AppScoped
class RemoteSecureSocialAuthenticatorPlugin @Inject()(
     shoeboxClient: ShoeboxServiceClient,
     healthcheckPlugin: HealthcheckPlugin,
     monitoredAwait: MonitoredAwait,
     app: Application
  ) extends AuthenticatorStore(app) with SecureSocialAuthenticatorPlugin {

  private def reportExceptions[T](f: => T): Either[Error, T] =
    try Right(f) catch { case ex: Throwable =>
      healthcheckPlugin.addError(
        HealthcheckError(error = Some(ex), method = None, path = None, callType = Healthcheck.INTERNAL))
      Left(new Error(ex))
    }

  private def authenticatorFromSession(session: UserSession): Authenticator = Authenticator(
    id = session.externalId.id,
    identityId = IdentityId(session.socialId.id, session.provider.name),
    creationDate = session.createdAt,
    lastUsed = session.updatedAt,
    expirationDate = session.expires
  )

  def save(authenticator: Authenticator): Either[Error, Unit] = reportExceptions { }
  def find(id: String): Either[Error, Option[Authenticator]] = reportExceptions {
    val externalIdOpt = try {
      Some(ExternalId[UserSession](id))
    } catch {
      case ex: Throwable => None
    }

    externalIdOpt flatMap { externalId =>
      val result = monitoredAwait.result(shoeboxClient.getSessionByExternalId(externalId), 3 seconds, s"get session for $externalId")
      result collect {
        case s if s.isValid => authenticatorFromSession(s)
      }
    }
  }
  def delete(id: String): Either[Error, Unit] = reportExceptions { }
}

@Singleton
class RemoteSecureSocialUserPlugin @Inject() (
     healthcheckPlugin: HealthcheckPlugin,
     shoeboxClient: ShoeboxServiceClient,
     monitoredAwait: MonitoredAwait
  ) extends UserService with SecureSocialUserPlugin with Logging {

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

  def find(id: IdentityId): Option[SocialUser] = reportExceptions {
    val resFuture = shoeboxClient.getSocialUserInfoByNetworkAndSocialId(SocialId(id.userId), SocialNetworkType(id.providerId))
    monitoredAwait.result(resFuture, 3 seconds, s"get user for social user ${id.userId} on $id.providerId") match {
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

case class RemoteSecureSocialModule() extends SecureSocialModule {
  def configure() {
    bind[ActionAuthenticator].to[RemoteActionAuthenticator]
  }

  @Singleton
  @Provides
  def secureSocialAuthenticatorPlugin(
    shoeboxClient: ShoeboxServiceClient,
    healthcheckPlugin: HealthcheckPlugin,
    monitoredAwait: MonitoredAwait,
    app: play.api.Application
  ): SecureSocialAuthenticatorPlugin = {
    new RemoteSecureSocialAuthenticatorPlugin(shoeboxClient, healthcheckPlugin, monitoredAwait, app)
  }

  @Singleton
  @Provides
  def secureSocialUserPlugin(
    healthcheckPlugin: HealthcheckPlugin,
    shoeboxClient: ShoeboxServiceClient,
    monitoredAwait: MonitoredAwait
  ): SecureSocialUserPlugin = {
    new RemoteSecureSocialUserPlugin(healthcheckPlugin, shoeboxClient, monitoredAwait)
  }

  @Singleton
  @Provides
  def templatesPlugin(app: play.api.Application): TemplatesPlugin = {
    new DefaultTemplatesPlugin(app)
  }
}

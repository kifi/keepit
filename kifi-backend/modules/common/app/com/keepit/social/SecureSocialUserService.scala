package com.keepit.social

import com.keepit.common.auth.LegacyUserService
import net.codingwell.scalaguice.InjectorExtensions._

import com.keepit.FortyTwoGlobal
import com.keepit.common.controller.KifiSession
import com.keepit.common.db.ExternalId

import play.api.Application
import play.api.mvc.{ Session, RequestHeader }
import securesocial.core._
import securesocial.core.providers.Token
import net.codingwell.scalaguice.ScalaModule
import KifiSession._

class SecureSocialIdGenerator(app: Application) extends IdGenerator(app) {
  def generate: String = ExternalId[String]().toString
}

class SecureSocialAuthenticatorStore(app: Application) extends AuthenticatorStore(app) {
  lazy val global = app.global.asInstanceOf[FortyTwoGlobal]
  lazy val plugin = global.injector.instance[SecureSocialAuthenticatorPlugin]
  def proxy: Option[SecureSocialAuthenticatorPlugin] = {
    if (global.initialized) Some(plugin) else None
  }
  def save(authenticator: Authenticator): Either[Error, Unit] = proxy.get.save(authenticator)
  def find(id: String): Either[Error, Option[Authenticator]] = proxy.get.find(id)
  def delete(id: String): Either[Error, Unit] = proxy.get.delete(id)
}

trait SecureSocialAuthenticatorPlugin {
  def save(authenticator: Authenticator): Either[Error, Unit]
  def find(id: String): Either[Error, Option[Authenticator]]
  def delete(id: String): Either[Error, Unit]
}

private class SecureSocialEventListener extends securesocial.core.EventListener {
  override val id = "fortytwo_event_listener"
  def onEvent(event: Event, request: RequestHeader, session: Session): Option[Session] = event match {
    case LogoutEvent(identity) =>
      // Remove our user ID info when the user logs out
      Some(session.deleteUserId)
    case _ =>
      None
  }
}

class SecureSocialUserService(implicit val application: Application) extends UserServicePlugin(application) {
  lazy val global = application.global.asInstanceOf[FortyTwoGlobal]

  def proxy: Option[SecureSocialUserPlugin] = {
    // Play will try to initialize this plugin before FortyTwoGlobal is fully initialized. This will cause
    // FortyTwoGlobal to attempt to initialize AppScope in multiple threads, causing deadlock. This allows us to wait
    // until the injector is initialized to do something if we want. When we need the plugin to be instantiated,
    // we can fail with None.get which will let us know immediately that there is a problem.
    if (global.initialized) Some(global.injector.instance[SecureSocialUserPlugin]) else None
  }
  def find(id: IdentityId): Option[Identity] = proxy.get.find(id)
  def save(user: Identity): Identity = proxy.get.save(user)

  def findByEmailAndProvider(email: String, providerId: String): Option[Identity] =
    proxy.get.findByEmailAndProvider(email, providerId)
  def save(token: Token) = proxy.get.save(token)
  def findToken(token: String) = proxy.get.findToken(token)
  def deleteToken(uuid: String) = proxy.get.deleteToken(uuid)
  def deleteExpiredTokens() {
    // Even if global is defined, getting the SecureSocialUserPlugin seems to cause deadlocks on start.
    // Fortunately our implementation of this method does nothing so it doesn't matter.
  }

  private val secureSocialEventListener = new SecureSocialEventListener

  override def onStart() {
    if (Registry.eventListeners.get(secureSocialEventListener.id).isEmpty) {
      Registry.eventListeners.register(secureSocialEventListener)
    }
    super.onStart()
  }

  override def onStop() {
    Registry.eventListeners.unRegister(secureSocialEventListener.id)
    cancellable.map(_.cancel())
  }
}

case class SecureSocialClientIds(linkedin: String, facebook: String)

trait SecureSocialUserPlugin extends LegacyUserService {

  def save(identity: Identity): Identity

  def findByEmailAndProvider(email: String, providerId: String): Option[Identity]
  def save(token: Token)
  def findToken(token: String): Option[Token]
  def deleteToken(uuid: String)
  def deleteExpiredTokens()
}

trait SecureSocialModule extends ScalaModule

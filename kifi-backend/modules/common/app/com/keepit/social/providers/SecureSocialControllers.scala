package com.keepit.social.providers

import com.keepit.FortyTwoGlobal
import com.keepit.common.controller.KifiSession
import com.keepit.common.logging.Logging
import com.keepit.social.UserIdentity
import play.api.mvc._
import play.api.i18n.Messages
import securesocial.core._
import play.api.{ Play, Logger }
import Play.current
import providers.utils.RoutesHelper
import securesocial.core.LoginEvent
import securesocial.core.AccessDeniedException
import securesocial.controllers.TemplatesPlugin

/**
 * A controller to provide the authentication entry point
 */
object ProviderController extends Controller with Logging {

  /**
   * The property that specifies the page the user is redirected to if there is no original URL saved in
   * the session.
   */
  val onLoginGoTo = "securesocial.onLoginGoTo"

  /**
   * The root path
   */
  val Root = "/"

  /**
   * The application context
   */
  val ApplicationContext = "application.context"

  /**
   * Returns the url that the user should be redirected to after login
   *
   * @param session
   * @return
   */
  def toUrl(session: Session) = session.get(SecureSocial.OriginalUrlKey).getOrElse(landingUrl)

  /**
   * The url where the user needs to be redirected after succesful authentication.
   *
   * @return
   */
  def landingUrl = Play.configuration.getString(onLoginGoTo).getOrElse(
    Play.configuration.getString(ApplicationContext).getOrElse(Root)
  )

  /**
   * Renders a not authorized page if the Authorization object passed to the action does not allow
   * execution.
   *
   * @see Authorization
   */
  def notAuthorized() = Action { implicit request =>
    import com.typesafe.plugin._
    Forbidden(use[TemplatesPlugin].getNotAuthorizedPage)
  }

  /**
   * The authentication flow for all providers starts here.
   *
   * @param provider The id of the provider that needs to handle the call
   * @return
   */
  def authenticate(provider: String) = handleAuth(provider)
  def authenticateByPost(provider: String) = handleAuth(provider)

  private def handleAuth(provider: String) = Action { implicit request =>
    Registry.providers.get(provider) match {
      case Some(p) => {
        try {
          p.authenticate().fold(result => result, {
            user => completeAuthentication(user, request.session)
          })
        } catch {
          case ex: AccessDeniedException => {
            Redirect(RoutesHelper.login()).flashing("error" -> Messages("securesocial.login.accessDenied"))
          }

          case other: Throwable => {
            Logger.error("Unable to log user in. An exception was thrown", other)
            Redirect(RoutesHelper.login()).flashing("error" -> Messages("securesocial.login.errorLoggingIn"))
          }
        }
      }
      case _ => NotFound
    }
  }

  def completeAuthentication(userIdentity: Identity, session: Session)(implicit request: RequestHeader): Result = {
    log.info(s"[securesocial] user logged in : [${userIdentity.email}] class=${userIdentity.getClass}")
    val sess = Events.fire(new LoginEvent(userIdentity)).getOrElse(session)
    Authenticator.create(userIdentity) match {
      case Right(authenticator) => {
        Redirect(toUrl(sess)).withSession(sess -
          SecureSocial.OriginalUrlKey -
          IdentityProvider.SessionId -
          OAuth1Provider.CacheKey).withCookies(authenticator.toCookie)
      }
      case Left(error) => {
        log.error(s"[completeAuthentication] Caught error $error while creating authenticator; cause=${error.getCause}")
        throw new RuntimeException("Error creating authenticator", error)
      }
    }
  }
}

import play.api.mvc.{ Action, Controller }
import securesocial.core._
import play.api.Play
import Play.current
import providers.utils.RoutesHelper
import KifiSession._

/**
 * The Login page controller
 */
object LoginPage extends Controller with Logging {
  import providers.UsernamePasswordProvider
  /**
   * The property that specifies the page the user is redirected to after logging out.
   */
  val onLogoutGoTo = "securesocial.onLogoutGoTo"

  /**
   * Renders the login page
   * @return
   */
  def login = Action { implicit request =>
    val to = ProviderController.landingUrl
    if (SecureSocial.currentUser.isDefined) {
      // if the user is already logged in just redirect to the app
      if (Logger.isDebugEnabled) {
        Logger.debug("User already logged in, skipping login page. Redirecting to %s".format(to))
      }
      Redirect(to)
    } else {
      import com.typesafe.plugin._
      if (SecureSocial.enableRefererAsOriginalUrl) {
        SecureSocial.withRefererAsOriginalUrl(Ok(use[TemplatesPlugin].getLoginPage(request, UsernamePasswordProvider.loginForm)))
      } else {
        import Play.current
        Ok(use[TemplatesPlugin].getLoginPage(request, UsernamePasswordProvider.loginForm))

      }
    }
  }

  /**
   * Logs out the user by clearing the credentials from the session.
   * The browser is redirected either to the login page or to the page specified in the onLogoutGoTo property.
   *
   * @return
   */
  def logout = Action { implicit request =>
    val to = Play.configuration.getString(onLogoutGoTo).getOrElse(RoutesHelper.login().absoluteURL(IdentityProvider.sslEnabled))
    val user = for (
      authenticator <- SecureSocial.authenticatorFromRequest;
      user <- UserService.find(authenticator.identityId)
    ) yield {
      Authenticator.delete(authenticator.id)
      user
    }
    val result = Redirect(to).discardingCookies(Authenticator.discardingCookie)
    log.info(s"[logout] user.email=${user.map(_.email)} user.class=${user.getClass}")
    user match {
      case Some(u) =>
        result.withSession(Events.fire(new LogoutEvent(u)).getOrElse(request.session).deleteUserId())
      case None =>
        result.withSession(request.session.deleteUserId())
    }
  }
}


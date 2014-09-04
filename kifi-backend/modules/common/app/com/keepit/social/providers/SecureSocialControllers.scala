package com.keepit.social.providers

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
object ProviderController extends Controller {

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
            user => completeAuthentication(user, request2session)
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

  def completeAuthentication(user: Identity, session: Session)(implicit request: RequestHeader): Result = {
    if (Logger.isDebugEnabled) {
      Logger.debug("[securesocial] user logged in : [" + user + "]")
    }
    val withSession = Events.fire(new LoginEvent(user)).getOrElse(session)
    Authenticator.create(user) match {
      case Right(authenticator) => {
        Redirect(toUrl(withSession)).withSession(withSession -
          SecureSocial.OriginalUrlKey -
          IdentityProvider.SessionId -
          OAuth1Provider.CacheKey).withCookies(authenticator.toCookie)
      }
      case Left(error) => {
        // improve this
        throw new RuntimeException("Error creating authenticator")
      }
    }
  }
}

import play.api.mvc.{ Action, Controller }
import securesocial.core._
import play.api.Play
import Play.current
import providers.utils.RoutesHelper

/**
 * The Login page controller
 */
object LoginPage extends Controller {
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
    user match {
      case Some(u) => result.withSession(Events.fire(new LogoutEvent(u)).getOrElse(request2session))
      case None => result
    }
  }
}


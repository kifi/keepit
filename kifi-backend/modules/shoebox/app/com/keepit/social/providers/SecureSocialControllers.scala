package com.keepit.social.providers

import com.keepit.common.controller.FortyTwoCookies
import com.keepit.common.healthcheck.AirbrakeNotifierStatic
import com.keepit.common.logging.Logging
import com.keepit.controllers.core.PostRegIntent
import com.keepit.social.IdentityHelpers
import play.api.Play.current
import play.api.i18n.Messages
import play.api.mvc._
import play.api.{ Logger, Play }
import securesocial.controllers.TemplatesPlugin
import securesocial.core.providers.utils.RoutesHelper
import securesocial.core.{ AccessDeniedException, LoginEvent, _ }

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
            user =>
              log.info(s"[handleAuth][$provider] user [${user.email} ${user.identityId}] found from provider - completing auth")
              completeAuthentication(user, request.session)
          }) match {
            case badResult if badResult.header.status == 400 =>
              log.error(s"[handleAuth][$provider] ${badResult.header.status} from $provider")
              val discardingCookies = Seq("intent", "publicLibraryId", "libAuthToken", "publicOrgId", "orgAuthToken", "publicKeepId", "keepAuthToken",
                "slackTeamId", "creditCode", "slackExtraScopes").filter(request.cookies.get(_).isDefined).map(DiscardingCookie(_))
              val failUrl = request.cookies.get("onFailUrl").map(_.value).getOrElse(toUrl(badResult.session))
              Redirect(failUrl, queryString = Map("error" -> Seq("access_denied"))).discardingCookies(discardingCookies: _*)
            case res => res
          }
        } catch {
          case ex: AccessDeniedException => {
            log.error(s"[handleAuth][$provider] Access Denied for user logging in", ex)
            Redirect(RoutesHelper.login()).flashing("error" -> Messages("securesocial.login.accessDenied"))
          }

          case other: Throwable => {
            val message = s"[handleAuth][$provider] Unable to log user in. An exception was thrown"
            log.error(message, other)
            AirbrakeNotifierStatic.notify(message, other)
            Redirect(RoutesHelper.login()).flashing("error" -> Messages("securesocial.login.errorLoggingIn"))
          }
        }
      }
      case _ => NotFound
    }
  }

  def completeAuthentication(userIdentity: Identity, session: Session)(implicit request: RequestHeader): Result = {
    log.info(s"[completeAuthentication] user logged in : [${userIdentity.email}] class=${userIdentity.getClass}")
    val sess = Events.fire(new LoginEvent(userIdentity)).getOrElse(session)
    Authenticator.create(userIdentity) match {
      case Right(authenticator) => {
        log.info(s"[completeAuthentication] Authentication [${authenticator.identityId}] completed for [${userIdentity.email}]")
        val slackTeamIdCookies = IdentityHelpers.parseSlackIdMaybe(userIdentity.identityId).toOption.map {
          case (slackTeamId, _) => Seq(Cookie("slackTeamId", slackTeamId.value), Cookie("intent", "slack"))
        }.getOrElse(Seq.empty)
        val kifiCookies = Seq(
          request.cookies.get("intent"),
          request.cookies.get("publicOrgId"),
          request.cookies.get("orgAuthToken"),
          request.cookies.get("publicLibraryId"),
          request.cookies.get("libAuthToken"),
          request.cookies.get("publicKeepId"),
          request.cookies.get("keepAuthToken"),
          request.cookies.get("slackExtraScopes")
        ).flatten ++ slackTeamIdCookies
        Redirect(toUrl(sess)).withSession(sess -
          SecureSocial.OriginalUrlKey -
          IdentityProvider.SessionId -
          OAuth1Provider.CacheKey).withCookies(Seq(authenticator.toCookie) ++ kifiCookies: _*)
      }
      case Left(error) => {
        log.error(s"[ProviderController.completeAuthentication] Caught error $error while creating authenticator; cause=${error.getCause}")
        throw new RuntimeException("Error creating authenticator", error)
      }
    }
  }
}

import com.keepit.common.controller.KifiSession._
import play.api.Play
import play.api.Play.current
import play.api.mvc.{ Action, Controller }
import securesocial.core._
import securesocial.core.providers.utils.RoutesHelper

/**
 * The Login page controller
 */
object LoginPage extends Controller with Logging {
  import securesocial.core.providers.UsernamePasswordProvider
  /**
   * The property that specifies the page the user is redirected to after logging out.
   */
  val onLogoutGoTo = "securesocial.onLogoutGoTo"

  /**
   * Renders the login page
   *
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
        import play.api.Play.current
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
    val cookies = new FortyTwoCookies.KifiInstallationCookie(current.configuration.getString("session.domain")).discard +: Authenticator.discardingCookie +: PostRegIntent.discardingCookies
    val result = Redirect(to).discardingCookies(cookies: _*)
    log.info(s"[logout] user.email=${user.map(_.email)} user.class=${user.getClass}")
    user match {
      case Some(u) =>
        result.withSession(Events.fire(new LogoutEvent(u)).getOrElse(request.session).deleteUserId())
      case None =>
        result.withSession(request.session.deleteUserId())
    }
  }
}


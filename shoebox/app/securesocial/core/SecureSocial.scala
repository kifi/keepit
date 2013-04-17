/**
 * Copyright 2012 Jorge Aliss (jaliss at gmail dot com) - twitter: @jaliss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package securesocial.core

import play.api.mvc._
import securesocial.controllers.routes
import play.api.i18n.Messages
import play.api.Logger
import play.api.libs.json.Json
import com.keepit.common.logging.Logging
import com.keepit.common.net._

/**
 * Provides the actions that can be used to protect controllers and retrieve the current user
 * if available.
 *
 * object MyController extends SecureSocial {
 *    def protectedAction = SecuredAction() { implicit request =>
 *      Ok("Hello %s".format(request.user.displayName))
 *    }
 */
trait SecureSocial extends Controller with Logging {

  /**
   * A request that adds the User for the current call
   */
  case class SecuredRequest[A](user: SocialUser, request: Request[A]) extends WrappedRequest(request)

  /**
   * A Forbidden response for API clients
   * @param request
   * @tparam A
   * @return
   */
  private def apiClientForbidden[A](implicit request: Request[A]): Result = {
    Forbidden(Json.toJson(Map("error"->"Credentials required"))).withSession {
      session - SecureSocial.UserKey - SecureSocial.ProviderKey
    }.as(JSON)
  }

  private def redirectToLoginPage[A] = { implicit request: Request[A] =>
    log.info("request.uri = %s".format(request.uri))
    Redirect(routes.LoginPage.login()).flashing("error" -> Messages("securesocial.loginRequired")).withSession(
      session + (SecureSocial.OriginalUrlKey -> request.uri)
    )
  }


  /**
   * A secured action.  If there is no user in the session the request is redirected
   * to the login page
   *
   * @param apiClient A boolean specifying if this is request is for an API or not
   * @param p
   * @param f
   * @tparam A
   * @return
   */
  def SecuredAction[A](apiClient: Boolean, p: BodyParser[A])(onAuthenticated: SecuredRequest[A] => Result, onUnauthenticated: Request[A] => Result = redirectToLoginPage) = Action(p) {
    implicit request => {
      log.debug("secured access (api=%s) to %s by %s".format(apiClient, request.path, request.agent))
      SecureSocial.userFromSession(request).map { userId =>
        UserService.find(userId).map { user =>
          onAuthenticated(SecuredRequest(user, request))
        }.getOrElse {
          // there is no user in the backing store matching the credentials sent by the client.
          // we need to remove the credentials from the session
          if ( apiClient ) {
            log.info("apiClientForbidden from %s to %s".format(request.agent, request.path))
            apiClientForbidden(request)
          } else {
            log.info("redirect to login")
            log.info("request.uri = %s".format(request.uri))
            //the following line is a FortyTwo update replacing the logout
            Redirect(routes.LoginPage.login()).flashing("error" -> Messages("securesocial.loginRequired")).withSession(
              session + (SecureSocial.OriginalUrlKey -> request.uri)
            )
          }
        }
      }.getOrElse {
        if ( apiClient ) {
          log.info("apiClientForbidden - anonymous user from %s to %s. user [%s], profider [%s]".format(
              request.agent, request.path,
              request.session.get(SecureSocial.UserKey).getOrElse("NO USER ID"),
              request.session.get(SecureSocial.ProviderKey).getOrElse("NO PROVIDER ID")))
          apiClientForbidden(request)
        } else {
          onUnauthenticated(request)
        }
      }
    }
  }

  /**
   * A secured action.  If there is no user in the session the request is redirected
   * to the login page.
   * @param f
   * @return
   */
  def SecuredAction(apiClient: Boolean = false)(onAuthenticated: SecuredRequest[AnyContent] => Result)(onUnauthenticated: Request[AnyContent] => Result = redirectToLoginPage): Action[AnyContent] = {
    SecuredAction(apiClient, parse.anyContent)(onAuthenticated, onUnauthenticated)
  }

  /**
   * A request that adds the User for the current call
   */
  case class RequestWithUser[A](user: Option[SocialUser], request: Request[A]) extends WrappedRequest(request)

  /**
   * An action that adds the current user in the request if it's available
   *
   * @param p
   * @param f
   * @tparam A
   * @return
   */
  def UserAwareAction[A](p: BodyParser[A])(f: RequestWithUser[A] => Result) = Action(p) {
    implicit request =>
      f(RequestWithUser(SecureSocial.currentUser, request))
  }

  /**
   * An action that adds the current user in the request if it's available
   * @param f
   * @return
   */
  def UserAwareAction(f: RequestWithUser[AnyContent] => Result): Action[AnyContent] = {
    UserAwareAction(parse.anyContent)(f)
  }
}

object SecureSocial {
  val UserKey = "securesocial.user"
  val ProviderKey = "securesocial.provider"
  val OriginalUrlKey = "securesocial.originalUrl"

  /**
   * Build a UserId object from the session data
   *
   * @param request
   * @tparam A
   * @return
   */
  def userFromSession[A](implicit request: Request[A]):Option[UserId] = {
    for (
      userId <- request.session.get(SecureSocial.UserKey);
      providerId <- request.session.get(SecureSocial.ProviderKey)
    ) yield {
      UserId(userId, providerId)
    }
  }

  /**
   * Get the current logged in user.  This method can be used from public actions that need to
   * access the current user if there's any
   *
   * @param request
   * @tparam A
   * @return
   */
  def currentUser[A](implicit request: Request[A]):Option[SocialUser] = {
    for (
      userId <- userFromSession ;
      user <- UserService.find(userId)
    ) yield {
      fillServiceInfo(user)
    }
  }

  def fillServiceInfo(user: SocialUser): SocialUser = {
    if ( user.authMethod == AuthenticationMethod.OAuth1 ) {
      // if the user is using OAuth1 make sure we're also returning
      // the right service info
      ProviderRegistry.get(user.id.providerId).map { p =>
        val si = p.asInstanceOf[OAuth1Provider].serviceInfo
        val oauthInfo = user.oAuth1Info.get.copy(serviceInfo = si)
        user.copy( oAuth1Info = Some(oauthInfo))
      }.get
    } else {
      user
    }
  }
}

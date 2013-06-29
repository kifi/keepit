package com.keepit.controllers.core

import com.keepit.common.controller.{ActionAuthenticator, ShoeboxServiceController}

import play.api.libs.json.Json
import play.api.mvc._
import securesocial.controllers.ProviderController
import securesocial.core._

class AuthController extends ShoeboxServiceController {

  // Some of this is copied from ProviderController in SecureSocial
  // We might be able to do this better but I'm just trying to get it working for now
  def mobileAuth(providerName: String) = Action(parse.json) { implicit request =>
  // e.g. { "accessToken": "..." }
    val oauth2Info = Json.fromJson(request.body)(Json.reads[OAuth2Info]).asOpt
    val provider = Registry.providers.get(providerName).get
    val authMethod = provider.authMethod
    val filledUser = provider.fillProfile(
      SocialUser(UserId("", providerName), "", "", "", None, None, authMethod, oAuth2Info = oauth2Info))
    UserService.find(filledUser.id) map { user =>
      val withSession = Events.fire(new LoginEvent(user)).getOrElse(session)
      Authenticator.create(user) match {
        case Right(authenticator) =>
          Ok(Json.obj("sessionId" -> authenticator.id)).withSession(
            withSession - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
              .withCookies(authenticator.toCookie)
        case Left(error) => throw error
      }
    } getOrElse {
      NotFound(Json.obj("error" -> "user not found"))
    }
  }

  // These methods are nice wrappers for the SecureSocial authentication methods.
  // Logout is still handled by SecureSocial directly.

  def login(provider: String) = getAuthAction(provider, isLogin = true)
  def loginByPost(provider: String) = getAuthAction(provider, isLogin = true)
  def link(provider: String) = getAuthAction(provider, isLogin = false)
  def linkByPost(provider: String) = getAuthAction(provider, isLogin = false)

  private def getAuthAction(provider: String, isLogin: Boolean): Action[AnyContent] = Action { implicit request =>
    ProviderController.authenticate(provider)(request) match {
      case res: SimpleResult[_] if isLogin =>
        val sesh = Session.decodeFromCookie(
          res.header.headers.get(SET_COOKIE).flatMap(Cookies.decode(_).find(_.name == Session.COOKIE_NAME)))
        res.withSession(sesh - ActionAuthenticator.FORTYTWO_USER_ID)
      case res => res
    }
  }
}

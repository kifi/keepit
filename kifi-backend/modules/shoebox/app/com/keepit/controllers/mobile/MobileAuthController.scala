package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.common.controller.{ShoeboxServiceController, ActionAuthenticator, MobileController}
import com.keepit.common.logging.Logging
import play.api.libs.json.Json
import securesocial.core._
import securesocial.core.LoginEvent
import securesocial.core.OAuth2Info
import securesocial.core.IdentityId
import play.api.mvc.{Result, Action}
import scala.util.{Failure, Success, Try}

class MobileAuthController @Inject() (
  actionAuthenticator:ActionAuthenticator
) extends MobileController(actionAuthenticator) with ShoeboxServiceController with Logging {

  // Note: some of the below code is taken from ProviderController in SecureSocial
  // Logout is still handled by SecureSocial directly.

  private implicit val readsOAuth2Info = Json.reads[OAuth2Info]

  // todo: revisit (adapted from mobileAuth)
  def accessTokenLogin(providerName: String) = Action(parse.json) { implicit request =>
    log.info(s"[accessTokenLogin($providerName)] ${request.body}")
    val resOpt:Option[Result] = for {
      oauth2Info <- request.body.asOpt[OAuth2Info]
      provider   <- Registry.providers.get(providerName)
    } yield {
      Try {
        provider.fillProfile(SocialUser(IdentityId("", provider.id), "", "", "", None, None, provider.authMethod, oAuth2Info = Some(oauth2Info)))
      } match {
        case Success(filledUser) =>
          UserService.find(filledUser.identityId) map { user =>
            val newSession = Events.fire(new LoginEvent(user)).getOrElse(session)
            Authenticator.create(user).fold(
              error => throw error, // todo: revisit
              authenticator => Ok(Json.obj("sessionId" -> authenticator.id))
                .withSession(newSession - SecureSocial.OriginalUrlKey - IdentityProvider.SessionId - OAuth1Provider.CacheKey)
                .withCookies(authenticator.toCookie)
            )
          } getOrElse NotFound(Json.obj("error" -> "user not found"))
        case Failure(t) =>
          log.error(s"[accessTokenLogin($provider)] Caught Exception($t) during fillProfile; token=${oauth2Info}; Cause:${t.getCause}; StackTrace: ${t.getStackTraceString}")
          BadRequest(Json.obj("error" -> "invalid token"))
      }
    }
    resOpt getOrElse BadRequest(Json.obj("error" -> "invalid arguments"))
  }

}

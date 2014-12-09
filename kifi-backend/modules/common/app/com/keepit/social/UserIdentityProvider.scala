package com.keepit.social

import com.keepit.common.controller.KifiSession._
import com.keepit.common.logging.Logging
import com.keepit.common.oauth.{ OAuth2Constants, OAuth2ProviderHelper }
import play.api.libs.json.{ JsNull, JsObject, JsNumber, JsString }
import play.api.libs.ws.WSResponse
import play.api.mvc._
import securesocial.core.{ UserService, Identity, IdentityProvider, OAuth2Info }

/**
 * An identity provider which returns UserIdentity instances. This allows us to know the currently logged in user when
 * SecureSocial authenticates the same user via a different social network.
 *
 * All our providers should extend this trait.
 */
trait UserIdentityProvider extends IdentityProvider with OAuth2ProviderHelper with Logging {

  abstract override def authenticate[A]()(implicit request: Request[A]): Either[Result, Identity] = {
    log.info(s"[authenticate] userId=${request.session.getUserId} session.data=${request.session.data} request=$request")
    val userIdOpt = request.session.getUserId
    doAuth()(request) match {
      case Right(socialUser) =>
        val filledSocialUser = fillProfile(socialUser)
        val saved = UserService.save(UserIdentity(userIdOpt, filledSocialUser))
        Right(saved)
      case left => left
    }
  }

  protected def buildInfo(response: WSResponse): OAuth2Info = buildTokenInfo(response)

}

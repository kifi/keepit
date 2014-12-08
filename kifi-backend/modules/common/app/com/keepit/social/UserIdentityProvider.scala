package com.keepit.social

import com.keepit.common.controller.KifiSession._
import com.keepit.common.logging.Logging
import com.keepit.common.oauth.OAuth2ProviderHelper
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsNumber, JsString, _ }
import play.api.libs.ws.WSResponse
import play.api.mvc._
import securesocial.core.{ IdentityId, OAuth2Info, _ }

import scala.concurrent.Await
import scala.concurrent.duration._

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

  // Next: skip doAuth() all together
  override def doAuth[A]()(implicit request: Request[A]): Either[Result, SocialUser] = {
    val call = doOAuth2() map { resOrToken =>
      resOrToken match {
        case Left(res) => Left(res)
        case Right(token) => Right(SocialUser(IdentityId("", id), "", "", "", None, None, authMethod, oAuth2Info = Some(token)))
      }
    }
    Await.result(call, 5 minutes)
  }

  protected def buildInfo(response: WSResponse): OAuth2Info = {
    val parsed = try {
      response.json.as[JsObject].value
    } catch {
      case _: Throwable =>
        response.body.split("&").map { kv =>
          val p = kv.split("=").take(2)
          p(0) -> (if (p.length == 2) {
            try { JsNumber(p(1).toInt) } catch {
              case _: Throwable => JsString(p(1))
            }
          } else JsNull)
        }.toMap
    }

    log.info(s"[buildInfo] parsed=$parsed")
    OAuth2Info(
      parsed.get(OAuth2Constants.AccessToken).map(_.as[String]).get,
      parsed.get(OAuth2Constants.TokenType).map(_.asOpt[String]).flatten,
      parsed.get(OAuth2Constants.ExpiresIn).map(_.asOpt[Int]).flatten,
      parsed.get(OAuth2Constants.RefreshToken).map(_.asOpt[String]).flatten
    )
  }

}

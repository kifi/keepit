package com.keepit.common.oauth.adaptor

import com.keepit.common.logging.Logging
import com.keepit.common.oauth._
import play.api.libs.ws.WSResponse
import play.api.mvc.{ Result, Request }
import play.api.libs.concurrent.Execution.Implicits._
import securesocial.core.{ OAuth2Info, IdentityId, SocialUser, OAuth2Provider }

import scala.concurrent.Await
import scala.concurrent.duration._

// Adaptor for SecureSocial OAuth2 providers
trait SecureSocialOAuth2Adaptor extends OAuth2Provider with Logging {

  def provider: OAuth2Support

  override protected def buildInfo(response: WSResponse): OAuth2Info = {
    try provider.buildTokenInfo(response) catch {
      case e: Throwable =>
        log.error(s"[buildInfo($id)] Failed to build oauth2 info. Response was ${response.body}")
        throw e
    }
  }

  override def fillProfile(user: SocialUser): SocialUser = {
    val socialUserF = provider.getUserProfileInfo(OAuth2AccessToken(user.oAuth2Info.get.accessToken)) map { info =>
      SecureSocialAdaptor.toSocialUser(info, user.authMethod).copy(oAuth2Info = user.oAuth2Info)
    }
    Await.result(socialUserF, 5 minutes)
  }

  override def doAuth[A]()(implicit request: Request[A]): Either[Result, SocialUser] = {
    val call = provider.doOAuth2() map { resOrToken =>
      resOrToken match {
        case Left(res) => Left(res)
        case Right(token) => Right(SocialUser(IdentityId("", id), "", "", "", None, None, authMethod, oAuth2Info = Some(token)))
      }
    }
    Await.result(call, 5 minutes)
  }

}

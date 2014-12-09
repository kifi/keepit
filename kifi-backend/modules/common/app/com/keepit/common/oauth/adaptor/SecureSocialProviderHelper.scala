package com.keepit.common.oauth.adaptor

import com.keepit.common.oauth._
import play.api.mvc.{ Result, Request }
import play.api.libs.concurrent.Execution.Implicits._
import securesocial.core.{ IdentityId, SocialUser, OAuth2Provider }

import scala.concurrent.Await
import scala.concurrent.duration._

trait SecureSocialProviderHelper { self: OAuth2Provider with OAuth2ProviderHelper =>
  def provider: OAuthProvider

  override def fillProfile(user: SocialUser): SocialUser = {
    val socialUserF = provider match {
      case o1: OAuth1Support =>
        o1.getUserProfileInfo(user.oAuth1Info.get) map { info =>
          SecureSocialAdaptor.toSocialUser(info, user.authMethod).copy(oAuth1Info = user.oAuth1Info)
        }
      case o2: OAuth2Support =>
        o2.getUserProfileInfo(OAuth2AccessToken(user.oAuth2Info.get.accessToken)) map { info =>
          SecureSocialAdaptor.toSocialUser(info, user.authMethod).copy(oAuth2Info = user.oAuth2Info)
        }
      case _ => throw new IllegalStateException(s"[fillProfile] provider=$provider not supported. user=$user")
    }
    Await.result(socialUserF, 5 minutes)
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

}

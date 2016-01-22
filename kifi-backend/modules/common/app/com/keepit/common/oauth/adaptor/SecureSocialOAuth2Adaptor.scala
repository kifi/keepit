package com.keepit.common.oauth.adaptor

import com.keepit.common.logging.Logging
import com.keepit.common.oauth._
import com.keepit.social.UserIdentity
import play.api.mvc.{ Result, Request }
import play.api.libs.concurrent.Execution.Implicits._
import securesocial.core.{ SocialUser, OAuth2Provider }

import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import com.keepit.common.core._

// Adaptor for SecureSocial OAuth2 providers
trait SecureSocialOAuth2Adaptor extends OAuth2Provider with Logging {

  // todo(Léo): Assuming refactor mentioned in TwitterProvider, perhaps merge this with UserIdentityProvider

  def provider: OAuth2Support[_ <: RichIdentity]

  override def doAuth[A]()(implicit request: Request[A]): Either[Result, UserIdentity] = {
    val call = provider.doOAuth() flatMap { resOrToken =>
      resOrToken match {
        case Left(res) => Future.successful(Left(res))
        case Right(token) => provider.getRichIdentity(token).imap(identity => Right(UserIdentity(identity)))
      }
    }
    Await.result(call, 5 minutes)
  }

}

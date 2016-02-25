package com.keepit.social.providers

import com.keepit.FortyTwoGlobal
import com.keepit.common.core._
import com.keepit.common.oauth._
import com.keepit.social.{ UserIdentity, UserIdentityProvider }
import net.codingwell.scalaguice.InjectorExtensions._
import play.api.Application
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.{ Request, Result }
import securesocial.core.{ AuthenticationMethod, IdentityProvider }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class SlackProvider(app: Application) extends IdentityProvider(app) with UserIdentityProvider {

  lazy val global = app.global.asInstanceOf[FortyTwoGlobal] // fail hard
  lazy val provider = global.injector.instance[SlackOAuthProviderImpl]

  def authMethod = AuthenticationMethod.OAuth2
  def id = provider.providerId.id

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

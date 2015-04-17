package com.keepit.social.providers

import com.keepit.FortyTwoGlobal
import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.common.oauth.OAuth2ProviderConfiguration
import com.keepit.model.User
import com.keepit.social.{ UserIdentity, UserIdentityProvider }

import play.api.Application
import play.api.data.Forms._
import play.api.data.{ Form, FormUtils }
import play.api.libs.iteratee.{ Enumerator, Iteratee }
import play.api.libs.json.{ JsValue, Json }
import play.api.mvc._
import play.api.mvc.Results.{ Forbidden, BadRequest }

import securesocial.core.providers.{ UsernamePasswordProvider => UPP }
import securesocial.core._
import play.api.libs.ws.WSResponse
import securesocial.core.IdentityId
import net.codingwell.scalaguice.InjectorExtensions._
import play.api.libs.json._

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{ Success, Failure }

class UsernamePasswordProvider(app: Application)
    extends UPP(app) with UserIdentityProvider with BodyParsers {

  // see SecureSocialAuthenticatorStore
  lazy val global = app.global.asInstanceOf[FortyTwoGlobal] // fail hard
  lazy val passwordAuth = global.injector.instance[PasswordAuthentication]
  lazy val providerConfig: OAuth2ProviderConfiguration = throw new UnsupportedOperationException(s"OAuth2 is not supported by provider $id")

  val loginForm = Form(
    tuple(
      "username" -> nonEmptyText,
      "password" -> nonEmptyText
    )
  )

  override def doAuth[A]()(implicit request: Request[A]): Either[Result, UserIdentity] = {
    loginForm.bindFromRequest().fold(
      errors => {
        log.warn(s"[UsernamePasswordProvider] Badness. Couldn't parse form. ${request.body.getClass.getCanonicalName}: ${request.body.toString}")
        Left(error("bad_form"))
      },
      credentials => {
        val (emailString, password) = credentials
        EmailAddress.validate(emailString.trim) match {
          case Failure(e) =>
            log.error(s"bad email format $emailString used for login - $e")
            Left(BadRequest("bad_email_format"))
          case Success(email) =>
            val identityId = IdentityId(email.address, id)
            UserService.find(identityId) match {
              case Some(identity) =>
                identity match {
                  case userIdentity: UserIdentity =>
                    log.info(s"[doAuth] userIdentity=$userIdentity")
                    if (passwordAuth.authenticate(userIdentity.userId.get, password)) Right(userIdentity) else Left(error("wrong_password"))
                  case _ =>
                    log.error(s"[doAuth] identity passed in is not of type <UserIdentity>; class=${identity.getClass}; obj=$identity")
                    Left(error("wrong_password")) // wrong_password for compatibility; auth_failure/internal_error more accurate
                }
              case None =>
                Left(error("no_such_user"))
            }
        }
      }
    )
  }

  private def error(errorCode: String) = Forbidden(Json.obj("error" -> errorCode))
}

trait PasswordAuthentication {
  def authenticate(userId: Id[User], providedCreds: String): Boolean
}

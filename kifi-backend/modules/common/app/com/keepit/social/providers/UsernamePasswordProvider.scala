package com.keepit.social.providers

import com.keepit.FortyTwoGlobal
import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.model.User
import com.keepit.social.{ UserIdentity, UserIdentityProvider }

import play.api.Application
import play.api.libs.json.Json
import play.api.mvc.{ Request, Result }
import play.api.mvc.Results.{ Forbidden, BadRequest }

import securesocial.core.providers.{ UsernamePasswordProvider => UPP }
import securesocial.core._
import play.api.libs.ws.WSResponse
import securesocial.core.IdentityId
import net.codingwell.scalaguice.InjectorExtensions._

import scala.util.{ Success, Failure }

class UsernamePasswordProvider(app: Application)
    extends UPP(app) with UserIdentityProvider {

  // see SecureSocialAuthenticatorStore
  lazy val global = app.global.asInstanceOf[FortyTwoGlobal] // fail hard
  lazy val passwordAuth = global.injector.instance[PasswordAuthentication]

  override def doAuth[A]()(implicit request: Request[A]): Either[Result, SocialUser] = {
    UPP.loginForm.bindFromRequest().fold(
      errors => Left(error("bad_form")),
      credentials => {
        val (emailString, password) = credentials
        EmailAddress.validate(emailString.trim) match {
          case Failure(e) =>
            log.error(s"bad email format $emailString used for login - $e")
            Left(BadRequest("bad_email_format"))
          case Success(email) =>
            UserService.find(IdentityId(email.address, id)) match {
              case Some(identity) =>
                identity match {
                  case socialUser: SocialUser =>
                    passwordAuth.authenticate(email, password) match {
                      case Left(t) =>
                        Left(error(t.getMessage))
                      case Right(userId) =>
                        Right(socialUser)
                    }
                  case _ =>
                    log.error(s"[doAuth] identity passed in is not of type <SocialUser>; class=${identity.getClass}; obj=$identity")
                    Left(error("wrong_password")) // wrong_password for compatibility; auth_failure/internal_error more accurate
                }

              case _ =>
                Left(error("no_such_user"))
            }
        }
      }
    )
  }

  override protected def buildInfo(response: WSResponse): OAuth2Info = {
    try super.buildInfo(response) catch {
      case e: Throwable =>
        log.info(s"[securesocial] Failed to build oauth2 info. Response was ${response.body}")
        throw e
    }
  }

  private def error(errorCode: String) = Forbidden(Json.obj("error" -> errorCode))
}

trait PasswordAuthentication {
  def authenticate(email: EmailAddress, creds: String): Either[Throwable, Id[User]]
}

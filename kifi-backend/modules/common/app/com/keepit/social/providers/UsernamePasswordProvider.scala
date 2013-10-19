package com.keepit.social.providers

import com.keepit.social.UserIdentityProvider

import play.api.Application
import play.api.mvc.{Results, Request, PlainResult, Result}
import play.api.mvc.Results.Redirect
import securesocial.core.providers.{UsernamePasswordProvider => UPP}
import securesocial.core.{Registry, UserService, IdentityId, SocialUser}
import play.api.libs.json.Json

class UsernamePasswordProvider(application: Application)
  extends UPP(application) with UserIdentityProvider {
  override def doAuth[A]()(implicit request: Request[A]): Either[Result, SocialUser] = {
    UPP.loginForm.bindFromRequest().fold(
      errors => Left(error(request, "bad_form", "Invalid email or password")),
      credentials => {
        UserService.find(IdentityId(credentials._1, id)) match {
          case Some(identity) =>
            val result = for {
              pinfo <- identity.passwordInfo
              hasher <- Registry.hashers.get(pinfo.hasher) if hasher.matches(pinfo, credentials._2)
            } yield Right(SocialUser(identity))
            result getOrElse Left(error(request, "bad_password", "Wrong password."))
          case None => Left(error(request, "no_user_exists", "No user with that email address exists."))
        }
      }
    )
  }

  private def error[A](request: Request[A], errorCode: String, errorString: String): PlainResult = {
    request.tags.get("format") match {
      case Some("json") =>
        Results.Forbidden(Json.obj("error" -> errorCode)).as("application/json")
      case _ => Redirect("/").flashing("error" -> errorString)
    }
  }
}

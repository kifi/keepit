package com.keepit.social.providers

import com.keepit.social.UserIdentityProvider

import play.api.Application
import play.api.libs.json.Json
import play.api.mvc.{Request, Result}
import play.api.mvc.Results.Forbidden

import securesocial.core.providers.{UsernamePasswordProvider => UPP}
import securesocial.core.{Registry, UserService, IdentityId, SocialUser}

class UsernamePasswordProvider(application: Application)
  extends UPP(application) with UserIdentityProvider {
  override def doAuth[A]()(implicit request: Request[A]): Either[Result, SocialUser] = {
    UPP.loginForm.bindFromRequest().fold(
      errors => Left(error("bad_form")),
      credentials => {
        UserService.find(IdentityId(credentials._1, id)) match {
          case Some(identity) =>
            val result = for {
              pInfo <- identity.passwordInfo
              hasher <- Registry.hashers.get(pInfo.hasher) if hasher.matches(pInfo, credentials._2)
            } yield Right(SocialUser(identity))
            result getOrElse Left(error("wrong_password"))
          case None =>
            Left(error("no_such_user"))
        }
      }
    )
  }

  private def error(errorCode: String) = Forbidden(Json.obj("error" -> errorCode))
}

package com.keepit.social.providers

import com.keepit.social.UserIdentityProvider

import play.api.Application
import play.api.mvc.Request
import play.api.mvc.Results.Redirect
import play.api.mvc.{PlainResult, Result}
import securesocial.core.providers.UsernamePasswordProvider
import securesocial.core.{Registry, UserService, IdentityId, SocialUser}

class UsernamePasswordProvider(application: Application)
  extends securesocial.core.providers.UsernamePasswordProvider(application) with UserIdentityProvider {
  override def doAuth[A]()(implicit request: Request[A]): Either[Result, SocialUser] = {
    UsernamePasswordProvider.loginForm.bindFromRequest().fold(
      errors => Left(error()),
      credentials => {
        val result = for {
          user <- UserService.find(IdentityId(credentials._1, id))
          pinfo <- user.passwordInfo
          hasher <- Registry.hashers.get(pinfo.hasher) if hasher.matches(pinfo, credentials._2)
        } yield Right(SocialUser(user))
        result getOrElse Left(error())
      }
    )
  }

  private def error[A](): PlainResult = {
    Redirect("/").flashing("error" ->
        "Invalid email and password. Try with different credentials, or create an account if you don't have one yet.")
  }
}

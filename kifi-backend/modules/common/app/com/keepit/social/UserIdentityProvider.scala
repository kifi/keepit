package com.keepit.social

import com.keepit.common.controller.KifiSession._
import com.keepit.common.logging.Logging
import play.api.mvc._
import securesocial.core.{ Identity, IdentityProvider, UserService }

/**
 * An identity provider which returns UserIdentity instances. This allows us to know the currently logged in user when
 * SecureSocial authenticates the same user via a different social network.
 */
trait UserIdentityProvider extends IdentityProvider with Logging {

  abstract override def authenticate[A]()(implicit request: Request[A]): Either[Result, Identity] = {
    val userIdOpt = request.session.getUserId
    log.info(s"[authenticate] userIdOpt=$userIdOpt session.data=${request.session.data} request=$request")
    doAuth()(request) match {
      case Right(socialUser) =>
        val filledSocialUser = fillProfile(socialUser)
        val saved = UserService.save(UserIdentity(userIdOpt, filledSocialUser))
        Right(saved)
      case left => left
    }
  }

}

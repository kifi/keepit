package com.keepit.social

import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.User

import play.api.mvc.{Result, Request}
import securesocial.core.{Identity, UserService, IdentityProvider}

/**
 * An identity provider which returns UserIdentity instances. This allows us to know the currently logged in user when
 * SecureSocial authenticates the same user via a different social network.
 *
 * All our providers should extend this trait.
 */
trait UserIdentityProvider extends IdentityProvider with Logging {
  abstract override def authenticate[A]()(implicit request: Request[A]): Either[Result, Identity] = {
    log.info(s"UserIdentityProvider got request: $request")
    log.info(s"session data: ${request.session.data}")
    val userIdOpt = request.session.get(ActionAuthenticator.FORTYTWO_USER_ID).map { id => Id[User](id.toLong) }
    doAuth() match {
      case Right(socialUser) =>
        val filledSocialUser = fillProfile(socialUser)
        val saved = UserService.save(UserIdentity(userIdOpt, filledSocialUser))
        Right(saved)
      case left => left
    }
  }
}

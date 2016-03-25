package com.keepit.social

import com.keepit.common.controller.KifiSession._
import com.keepit.common.logging.Logging
import play.api.mvc._
import securesocial.core.{ Identity, IdentityProvider, SocialUser, UserService }

/**
 * An identity provider which returns UserIdentity instances. This allows us to know the currently logged in user when
 * SecureSocial authenticates the same user via a different social network.
 */
trait UserIdentityProvider extends Logging { self: IdentityProvider =>

  override def fillProfile(socialUser: SocialUser): SocialUser = socialUser

  override def authenticate[A]()(implicit request: Request[A]): Either[Result, Identity] = {
    val userIdOpt = request.session.getUserId
    log.info(s"[authenticate] userIdOpt=$userIdOpt session.data=${request.session.data} request=$request")
    doAuth()(request) match {
      case Right(UserIdentity(identity, existingUserIdOpt)) =>
        val saved = UserService.save(UserIdentity(identity, userIdOpt orElse existingUserIdOpt))
        Right(saved)
      case Right(socialUser) => throw new IllegalStateException(s"Unexpected SocialUser: $socialUser")
      case left => left
    }
  }

}

package com.keepit.social

import play.api.Logger
import play.api.mvc.Request
import play.api.mvc.Result
import securesocial.core.{SocialUser, IdentityProvider}

/**
 * All our identity providers should mix in this trait
 */
trait LoggingProvider extends IdentityProvider {
  abstract override def doAuth[A]()(implicit request: Request[A]): Either[Result, SocialUser] = {
    Logger.info(s"[securesocial] request: $request")
    super.doAuth()(request)
  }
}

package com.keepit.social.providers

import java.util.UUID

import com.keepit.FortyTwoGlobal
import com.keepit.common.core._
import com.keepit.common.logging.Logging
import com.keepit.common.oauth.TwitterOAuthProvider
import com.keepit.social.{ UserIdentity, UserIdentityProvider }
import net.codingwell.scalaguice.InjectorExtensions._
import play.api.Application
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.oauth.RequestToken
import play.api.mvc.Results._
import play.api.mvc.{ Request, Result }
import securesocial.core._
import securesocial.core.providers.utils.RoutesHelper

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class TwitterProvider(app: Application) extends securesocial.core.providers.TwitterProvider(app) with UserIdentityProvider with Logging {

  lazy val global = app.global.asInstanceOf[FortyTwoGlobal] // fail hard
  lazy val provider = global.injector.instance[TwitterOAuthProvider]

  override def doAuth[A]()(implicit request: Request[A]): Either[Result, UserIdentity] = {
    val call = {
      doOAuth() match {
        case Left(res) => Future.successful(Left(res))
        case Right(token) => provider.getRichIdentity(token).imap(identity => Right(UserIdentity(identity)))
      }
    }
    Await.result(call, 5 minutes)
  }

  // todo(Léo): maybe move this to something like OAuth1ProviderHelper
  private def doOAuth[A]()(implicit request: Request[A]): Either[Result, OAuth1Info] = {
    if (request.queryString.get("denied").isDefined) {
      // the user did not grant access to the account
      throw new AccessDeniedException()
    }

    request.queryString.get("oauth_verifier").map { seq =>
      val verifier = seq.head
      // 2nd step in the oauth flow, we have the request token in the cache, we need to
      // swap it for the access token
      val user = for {
        cacheKey <- request.session.get(OAuth1Provider.CacheKey) // do something about cache
        requestToken <- Cache.getAs[RequestToken](cacheKey)
      } yield {
        log.info(s"[doAuth($id).2] cacheKey=$cacheKey requestToken=$requestToken")
        service.retrieveAccessToken(RequestToken(requestToken.token, requestToken.secret), verifier) match {
          case Right(accessToken) =>
            log.info(s"[doAuth($id).2] accessToken=$accessToken") // why is it typed RequestToken?
            // the Cache api does not have a remove method.  Just set the cache key and expire it after 1 second for now
            Cache.set(cacheKey, "", 1)
            Right(OAuth1Info(accessToken.token, accessToken.secret))
          case Left(oauthException) =>
            log.error(s"[doAuth($id).2] error retrieving access token. Error: $oauthException")
            throw new AuthenticationException()
        }
      }
      user.getOrElse(throw new AuthenticationException())
    }.getOrElse {
      // the oauth_verifier field is not in the request, this is the 1st step in the auth flow.
      // we need to get the request tokens
      val callbackUrl = RoutesHelper.authenticate(id).absoluteURL(IdentityProvider.sslEnabled)
      log.info(s"[doAuth($id).1] callback url = $callbackUrl")
      service.retrieveRequestToken(callbackUrl) match {
        case Right(requestToken) =>
          val cacheKey = UUID.randomUUID().toString
          val redirect = Redirect(service.redirectUrl(requestToken.token)).withSession(request.session + (OAuth1Provider.CacheKey -> cacheKey))
          Cache.set(cacheKey, requestToken, 600) // set it for 10 minutes, plenty of time to log in
          Left(redirect)
        case Left(e) =>
          log.error(s"[doAuth($id).1] error retrieving request token. Error: $e")
          throw new AuthenticationException()
      }
    }
  }

}

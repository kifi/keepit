package com.keepit.social.providers

import java.util.UUID

import com.keepit.FortyTwoGlobal
import com.keepit.common.controller.KifiSession._
import com.keepit.common.logging.Logging
import com.keepit.common.oauth2.TwitterOAuthProvider
import com.keepit.common.oauth2.adaptor.{ SecureSocialAdaptor }
import com.keepit.social.UserIdentity
import play.api.Application
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.oauth.{ RequestToken }
import play.api.mvc.Results._
import play.api.mvc.{ Request, Result }
import securesocial.core._
import securesocial.core.providers.utils.RoutesHelper
import net.codingwell.scalaguice.InjectorExtensions._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Await
import scala.concurrent.duration._

class TwitterProvider(app: Application) extends securesocial.core.providers.TwitterProvider(app) with Logging {

  lazy val global = app.global.asInstanceOf[FortyTwoGlobal] // fail hard
  lazy val provider = global.injector.instance[TwitterOAuthProvider]

  override def fillProfile(user: SocialUser): SocialUser = {
    val socialUserF = provider.getUserProfileInfo(user.oAuth1Info.get) map { info =>
      SecureSocialAdaptor.toSocialUser(info, user.authMethod).copy(oAuth1Info = user.oAuth1Info)
    }
    Await.result(socialUserF, 5 minutes)
  }

  override def authenticate[A]()(implicit request: Request[A]): Either[Result, Identity] = {
    log.info(s"UserIdentityProvider got request: $request")
    log.info(s"session data: ${request.session.data}")
    val userIdOpt = request.session.getUserId
    doAuth()(request) match {
      case Right(socialUser) =>
        val filledSocialUser = fillProfile(socialUser)
        val saved = UserService.save(UserIdentity(userIdOpt, filledSocialUser))
        Right(saved)
      case left => left
    }
  }

  override def doAuth[A]()(implicit request: Request[A]): Either[Result, SocialUser] = {
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
            Right(
              SocialUser(
                IdentityId("", id), "", "", "", None, None, authMethod,
                oAuth1Info = Some(OAuth1Info(accessToken.token, accessToken.secret))
              )
            )
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

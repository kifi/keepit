package com.keepit.social.providers

import java.util.UUID

import com.keepit.common.controller.KifiSession
import com.keepit.common.logging.Logging
import com.keepit.social.{ UserIdentity, UserIdentityProvider }
import play.api.cache.Cache
import play.api.mvc.Results._
import play.api.mvc.{ Result, Request }
import play.api.{ Application }
import play.api.libs.oauth.{ RequestToken, OAuthCalculator }
import play.api.libs.ws.{ WS, WSResponse }
import securesocial.core._
import securesocial.core.providers.utils.RoutesHelper
import play.api.Play.current
import KifiSession._

class TwitterProvider(app: Application) extends securesocial.core.providers.TwitterProvider(app) /* with UserIdentityProvider */ with Logging {

  import securesocial.core.providers.TwitterProvider
  import securesocial.core.providers.TwitterProvider._
  override def fillProfile(user: SocialUser): SocialUser = {
    val oauthInfo = user.oAuth1Info.get
    val call = WS.url(TwitterProvider.VerifyCredentials).sign(
      OAuthCalculator(SecureSocial.serviceInfoFor(user).get.key,
        RequestToken(oauthInfo.token, oauthInfo.secret))
    ).get()

    try {
      val response = awaitResult(call)
      log.info(s"[fillProfile] response.body=${response.body}")
      val me = response.json
      // should get screen name and follower count at a minimum
      val userId = (me \ Id).as[Long]
      val name = (me \ Name).as[String]
      val splitted = name.split(' ')
      val (firstName, lastName) = if (splitted.length < 2) (name, "") else (splitted.head, splitted.takeRight(1).head)
      val profileImage = (me \ ProfileImage).asOpt[String]
      user.copy(identityId = IdentityId(userId.toString, id), fullName = name, firstName = firstName, lastName = lastName, avatarUrl = profileImage)

    } catch {
      case e: Exception => {
        log.error("[securesocial] error retrieving profile information from Twitter", e)
        throw new AuthenticationException()
      }
    }
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

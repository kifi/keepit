package com.keepit.common.oauth

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.auth.AuthException
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.social.twitter.TwitterHandle
import com.kifi.macros.json
import com.ning.http.client.providers.netty.NettyResponse
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.oauth._
import play.api.libs.ws.WS
import securesocial.core.{ AuthenticationMethod, SocialUser, OAuth1Info, IdentityId }

import scala.concurrent.Future

@json case class TwitterUserShow(
  profile_banner_url: Option[String],
  followers_count: Option[Int],
  description: Option[String],
  friends_count: Option[Int],
  `protected`: Option[Boolean],
  statuses_count: Option[Int],
  favourites_count: Option[Int])

object TwitterOAuthProvider {
  def toIdentity(auth: OAuth1Info, info: TwitterUserInfo): TwitterIdentity = {
    val socialUser = SocialUser(
      identityId = IdentityId(info.id.toString, ProviderIds.Twitter.id),
      firstName = info.firstName,
      lastName = info.lastName,
      fullName = info.name,
      avatarUrl = info.pictureUrl,
      email = None,
      authMethod = AuthenticationMethod.OAuth1,
      oAuth1Info = Some(auth)
    )
    TwitterIdentity(socialUser, info.pictureUrl, Some(info.profileUrl))
  }
}

trait TwitterOAuthProvider extends OAuth1Support[TwitterIdentity] {
  val providerId = ProviderIds.Twitter

  def getUserShow(accessToken: OAuth1TokenInfo, screenName: TwitterHandle): Future[TwitterUserShow]
}

@Singleton
class TwitterOAuthProviderImpl @Inject() (
    airbrake: AirbrakeNotifier,
    oauth1Config: OAuth1Configuration) extends TwitterOAuthProvider with Logging {

  val providerConfig = oauth1Config.getProviderConfig(providerId.id).get

  private val verifyCredsEndpoint = "https://api.twitter.com/1.1/account/verify_credentials.json"

  def getIdentityId(accessToken: OAuth1TokenInfo): Future[IdentityId] = getRichIdentity(accessToken).map(RichIdentity.toIdentityId)

  def getRichIdentity(accessToken: OAuth1TokenInfo): Future[TwitterIdentity] = {
    getUserProfileInfo(accessToken) map { info =>
      TwitterOAuthProvider.toIdentity(accessToken, info)
    }
  }

  private def getUserProfileInfo(accessToken: OAuth1TokenInfo): Future[TwitterUserInfo] = {
    val call = WS.url(verifyCredsEndpoint)
      .sign(OAuthCalculator(providerConfig.key, accessToken))
      .withQueryString("include_entities" -> false.toString, "skip_status" -> true.toString)
      .get()
    call map { resp =>
      if (resp.status == 429) {
        throw new AuthException(s"Rate limited. status=${resp.status} body=${resp.body};", resp)
      } else if (resp.status != 200) {
        throw new AuthException(s"[fetchSocialUserInfo] non-OK response from $verifyCredsEndpoint. status=${resp.status} body=${resp.body}; request=${resp.underlying[NettyResponse]} request.uri=${resp.underlying[NettyResponse].getUri}", resp)
      } else {
        resp.json.asOpt[TwitterUserInfo] match {
          case None =>
            throw new AuthException(s"[fillProfile] Failed to parse response.body=${resp.body}", resp)
          case Some(tui) => tui
        }
      }
    }
  }

  // Returns several useful fields about a user
  // https://dev.twitter.com/rest/reference/get/users/show
  def getUserShow(accessToken: OAuth1TokenInfo, screenName: TwitterHandle): Future[TwitterUserShow] = {
    val call = WS.url("https://api.twitter.com/1.1/users/show.json")
      .sign(OAuthCalculator(providerConfig.key, accessToken))
      .withQueryString("screen_name" -> screenName.value)
      .get()
    call.map { resp =>
      if (resp.status != 200) {
        throw new RuntimeException(s"[getUserShow] Non-200 response for $screenName. ${resp.body}")
      } else {
        resp.json.as[TwitterUserShow]
      }
    }
  }

}

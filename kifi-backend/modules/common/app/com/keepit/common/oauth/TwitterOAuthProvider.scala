package com.keepit.common.oauth

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.auth.AuthException
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model.OAuth1TokenInfo
import com.kifi.macros.json
import com.ning.http.client.providers.netty.NettyResponse
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.JsObject
import play.api.libs.oauth._
import play.api.libs.ws.WS

import scala.concurrent.Future

@json case class TwitterUserShow(
  profile_banner_url: Option[String],
  followers_count: Option[Int],
  description: Option[String],
  friends_count: Option[Int])

trait TwitterOAuthProvider extends OAuthProvider with OAuth1Support {
  val providerId = ProviderIds.Twitter

  def getUserShow(accessToken: OAuth1TokenInfo, screenName: String): Future[TwitterUserShow]
}

@Singleton
class TwitterOAuthProviderImpl @Inject() (
    airbrake: AirbrakeNotifier,
    oauth1Config: OAuth1Configuration) extends TwitterOAuthProvider with OAuth1Support with Logging {

  val providerConfig = oauth1Config.getProviderConfig(providerId.id).get

  private val verifyCredsEndpoint = "https://api.twitter.com/1.1/account/verify_credentials.json"

  def getUserProfileInfo(accessToken: OAuth1TokenInfo): Future[UserProfileInfo] = {
    val call = WS.url(verifyCredsEndpoint)
      .sign(OAuthCalculator(providerConfig.key, accessToken))
      .withQueryString("include_entities" -> false.toString, "skip_status" -> true.toString)
      .get()
    call map { resp =>
      if (resp.status != 200) {
        throw new AuthException(s"[fetchSocialUserInfo] non-OK response from $verifyCredsEndpoint. status=${resp.status} body=${resp.body}; request=${resp.underlying[NettyResponse]} request.uri=${resp.underlying[NettyResponse].getUri}")
      } else {
        resp.json.asOpt[TwitterUserInfo] match {
          case None =>
            throw new AuthException(s"[fillProfile] Failed to parse response.body=${resp.body}")
          case Some(tui) =>
            log.info(s"[fillProfile] tui=$tui; response.body=${resp.body}")
            TwitterUserInfo.toUserProfileInfo(tui)
        }
      }
    }
  }

  // Returns several useful fields about a user
  // https://dev.twitter.com/rest/reference/get/users/show
  def getUserShow(accessToken: OAuth1TokenInfo, screenName: String): Future[TwitterUserShow] = {
    val call = WS.url("https://api.twitter.com/1.1/users/show.json")
      .sign(OAuthCalculator(providerConfig.key, accessToken))
      .withQueryString("screen_name" -> screenName)
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

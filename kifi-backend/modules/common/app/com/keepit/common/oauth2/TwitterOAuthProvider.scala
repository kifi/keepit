package com.keepit.common.oauth2

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model.OAuth1TokenInfo
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.oauth._
import play.api.libs.ws.WS

import scala.concurrent.Future

/**
 * Twitter is (mostly) OAuth1a. May need to rename package for accuracy.
 */
trait TwitterOAuthProvider extends OAuthProvider with OAuth1Support {

  val VerifyCredentials = "https://api.twitter.com/1.1/account/verify_credentials.json"
  val Twitter = "twitter"
  val Id = "id"
  val Name = "name"
  val ProfileImage = "profile_image_url_https"

  val consumerKey = ConsumerKey(key = "cwXfTNd8iiKbWtXtszz9ADNmQ", secret = "sO2GthBWUMhNG7WYp0gyBq4yLpSzVlJkdVPjfaxhTEe92ZfPS1")

  val serviceInfo = ServiceInfo(
    requestTokenURL = "https://twitter.com/oauth/request_token",
    accessTokenURL = "https://twitter.com/oauth/access_token",
    authorizationURL = "https://twitter.com/oauth/authenticate",
    key = consumerKey
  )

  val providerId = ProviderIds.Twitter

}

@Singleton
class TwitterOAuthProviderImpl @Inject() (
    airbrake: AirbrakeNotifier) extends TwitterOAuthProvider with OAuth1Support with Logging {
  def getUserProfileInfo(accessToken: OAuth1TokenInfo): Future[UserProfileInfo] = {
    val call = WS.url(VerifyCredentials)
      .sign(OAuthCalculator(serviceInfo.key, accessToken))
      .get()

    call map { response =>
      log.info(s"[fillProfile] response.body=${response.body}")
      val me = response.json
      // should get screen name and follower count at a minimum
      val userId = (me \ Id).as[Long]
      val name = (me \ Name).as[String]
      val splitted = name.split(' ')
      val (firstName, lastName) = if (splitted.length < 2) (name, "") else (splitted.head, splitted.takeRight(1).head)
      val profileImage = (me \ ProfileImage).asOpt[String]
      UserProfileInfo(
        providerId = providerId,
        userId = ProviderUserId(userId.toString),
        name = name,
        emailOpt = None,
        firstNameOpt = Some(firstName),
        lastNameOpt = if (lastName.isEmpty) None else Some(lastName),
        pictureUrl = profileImage.map(new java.net.URL(_))
      )
    }
  }

}

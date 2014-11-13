package com.keepit.common.oauth2

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.social.providers.LinkedInProvider
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.JsArray
import play.api.libs.ws.WS

import scala.concurrent.Future

@Singleton
class LinkedInOAuthProvider @Inject() (airbrake: AirbrakeNotifier) extends OAuthProvider with Logging {

  val providerId: ProviderId = ProviderIds.LinkedIn

  import com.keepit.social.providers.LinkedInProvider._
  def getUserProfileInfo(accessToken: OAuth2AccessToken): Future[UserProfileInfo] = {
    WS.url(LinkedInProvider.Api + accessToken).withRequestTimeout(120000).get() map { response =>
      val me = response.json
      (me \ ErrorCode).asOpt[Int] match {
        case Some(code) => {
          val message = (me \ Message).asOpt[String]
          val requestId = (me \ RequestId).asOpt[String]
          val timestamp = (me \ Timestamp).asOpt[String]
          val exMsg = s"[getUserProfileInfo] error retrieving profile info from LinkedIn. Error code=$code requestId=$requestId message=$message timestamp=$timestamp"
          airbrake.notify(exMsg)
          throw new AuthException(exMsg)
        }
        case _ => {
          val userId = (me \ Id).as[String]
          val firstName = (me \ FirstName).asOpt[String]
          val lastName = (me \ LastName).asOpt[String]
          val fullName = (me \ FormattedName).asOpt[String].getOrElse("")
          val emailAddress = (me \ EmailAddr).asOpt[String]
          val avatarUrl = (me \ PictureUrl \ "values").asOpt[JsArray].map(_(0).asOpt[String]).flatten
          UserProfileInfo(
            providerId = providerId,
            userId = ProviderUserId(userId),
            name = fullName,
            emailOpt = emailAddress.map(EmailAddress(_)),
            firstNameOpt = firstName,
            lastNameOpt = lastName,
            pictureUrl = avatarUrl.map(new java.net.URL(_))
          )
        }
      }
    }
  }

}

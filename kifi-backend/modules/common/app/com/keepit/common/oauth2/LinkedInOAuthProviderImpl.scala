package com.keepit.common.oauth2

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.auth.AuthException
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.model.OAuth2TokenInfo
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.JsArray
import play.api.libs.ws.WS

import scala.concurrent.Future

trait LinkedInOAuthProvider extends OAuthProvider {
  val providerId = ProviderIds.LinkedIn
}

object LinkedInOAuthProvider {
  def api(accessToken: OAuth2AccessToken) = s"https://api.linkedin.com/v1/people/~:(id,first-name,last-name,email-address,formatted-name,picture-urls::(original);secure=true)?format=json&oauth2_access_token=${accessToken.token}"
  val LinkedIn = "linkedin"
  val ErrorCode = "errorCode"
  val Message = "message"
  val RequestId = "requestId"
  val Timestamp = "timestamp"
  val Id = "id"
  val FirstName = "firstName"
  val LastName = "lastName"
  val EmailAddr = "emailAddress"
  val FormattedName = "formattedName"
  val PictureUrl = "pictureUrls"
}

@Singleton
class LinkedInOAuthProviderImpl @Inject() (airbrake: AirbrakeNotifier) extends LinkedInOAuthProvider with Logging {

  import LinkedInOAuthProvider._
  def getUserProfileInfo(accessToken: OAuth2AccessToken): Future[UserProfileInfo] = {
    WS.url(api(accessToken)).withRequestTimeout(120000).get() map { response =>
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

  // not supported -- LinkedIn OAuth limitations
  def exchangeLongTermToken(tokenInfo: OAuth2TokenInfo): Future[OAuth2TokenInfo] = Future.successful(tokenInfo)

}

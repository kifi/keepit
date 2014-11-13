package com.keepit.common.oauth2

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import play.api.libs.json.JsObject
import play.api.libs.ws.WS
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import play.api.Play.current

@Singleton
class FacebookOAuthProvider @Inject() (airbrake: AirbrakeNotifier) extends OAuthProvider with Logging {

  val MeApi = "https://graph.facebook.com/me?fields=name,first_name,last_name,picture,email&return_ssl_resources=1&access_token="
  val Error = "error"
  val Message = "message"
  val Type = "type"
  val Id = "id"
  val FirstName = "first_name"
  val LastName = "last_name"
  val Name = "name"
  val Picture = "picture"
  val Email = "email"
  val AccessToken = "access_token"
  val Expires = "expires"
  val Data = "data"
  val Url = "url"

  val providerId = ProviderIds.Facebook

  def getUserProfileInfo(accessToken: OAuth2AccessToken): Future[UserProfileInfo] = {
    WS.url(MeApi + accessToken).get() map { response =>
      log.info(s"[getUserProfileInfo] response=${response.body} status=${response.statusText}")
      val me = response.json
      (me \ Error).asOpt[JsObject] match {
        case Some(error) =>
          val message = (error \ Message).as[String]
          val errorType = (error \ Type).as[String]
          val exMsg = s"[getUserProfileInfo] error retrieving profile info from Facebook. errorType=$errorType, msg=$message"
          airbrake.notify(exMsg)
          throw new AuthException(exMsg)
        case _ =>
          val userId = (me \ Id).as[String]
          val name = (me \ Name).as[String]
          val firstName = (me \ FirstName).asOpt[String]
          val lastName = (me \ LastName).asOpt[String]
          val picture = (me \ Picture)
          val avatarUrl = (picture \ Data \ Url).asOpt[String]
          val email = (me \ Email).asOpt[String]
          UserProfileInfo(
            providerId = providerId,
            userId = ProviderUserId(userId),
            name = name,
            emailOpt = email.map(EmailAddress(_)),
            firstNameOpt = firstName,
            lastNameOpt = lastName,
            pictureUrl = avatarUrl.map(new java.net.URL(_))
          )
      }
    }
  }

}

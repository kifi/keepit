package com.keepit.common.oauth

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.auth.AuthException
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.model.OAuth2TokenInfo
import play.api.http.Status
import play.api.libs.json.{ JsNumber, JsString, JsNull, JsObject }
import play.api.libs.ws.{ WSResponse, WS }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import play.api.Play.current

trait FacebookOAuthProvider extends OAuth2Support {

  val MeApi = "https://graph.facebook.com/v2.0/me?fields=name,first_name,last_name,picture.type(large),email&return_ssl_resources=1&access_token="
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
}

@Singleton
class FacebookOAuthProviderImpl @Inject() (
  airbrake: AirbrakeNotifier,
  val oauth2Config: OAuth2Configuration)
    extends FacebookOAuthProvider
    with FacebookOAuth2ProviderHelper
    with Logging {

  def getUserProfileInfo(accessToken: OAuth2AccessToken): Future[UserProfileInfo] = {
    WS.url(MeApi + accessToken.token).get() map { response =>
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
          val picture = me \ Picture
          val avatarUrl = (picture \ Data \ Url).asOpt[String]
          val email = (me \ Email).asOpt[String]
          UserProfileInfo(
            providerId = providerId,
            userId = ProviderUserId(userId),
            name = name,
            emailOpt = email.map(EmailAddress(_)),
            firstNameOpt = firstName,
            lastNameOpt = lastName,
            handle = None,
            pictureUrl = avatarUrl.map(new java.net.URL(_)),
            profileUrl = Some(new java.net.URL(s"http://facebook.com/$userId"))
          )
      }
    }
  }

  def exchangeLongTermToken(oauth2Info: OAuth2TokenInfo): Future[OAuth2TokenInfo] = {
    val resF = WS.url(providerConfig.exchangeTokenUrl.get.toString).withQueryString(
      "grant_type" -> "fb_exchange_token",
      "client_id" -> providerConfig.clientId,
      "client_secret" -> providerConfig.clientSecret,
      "fb_exchange_token" -> oauth2Info.accessToken.token
    ).get
    resF map { res =>
      log.info(s"[exchangeToken] response=${res.body}")
      if (res.status == Status.OK) {
        val params = res.body.split('&').map { token =>
          val nv = token.split('=')
          nv(0) -> nv(1)
        }.toMap
        oauth2Info.copy(accessToken = OAuth2AccessToken(params("access_token")), expiresIn = params.get("expires").map(_.toInt))
      } else {
        log.warn(s"[exchangeToken] failed to obtain exchange token. status=${res.statusText} resp=${res.body} oauth2Info=$oauth2Info; config=$providerConfig")
        oauth2Info
      }
    } recover {
      case t: Throwable =>
        airbrake.notify(s"[exchangeToken] Caught exception $t during exchange attempt. Cause=${t.getCause}. Fallback to $oauth2Info", t)
        oauth2Info
    }
  }
}

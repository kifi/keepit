package com.keepit.common.oauth

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.auth.AuthException
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import play.api.http.Status
import play.api.libs.json.{ JsNumber, JsString, JsNull, JsObject }
import play.api.libs.ws.{ WSResponse, WS }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import securesocial.core.{ AuthenticationMethod, SocialUser, OAuth2Info, IdentityId }

import scala.concurrent.Future
import play.api.Play.current

case class FacebookAPIError(error: String, message: String) extends Exception(s"Error $error while calling Facebook: $message")

object FacebookOAuthProvider {
  def toIdentity(auth: OAuth2Info, info: UserProfileInfo): FacebookIdentity = {
    val socialUser = SocialUser(
      identityId = IdentityId(info.userId.id, ProviderIds.Facebook.id),
      firstName = info.firstNameOpt getOrElse "",
      lastName = info.lastNameOpt getOrElse "",
      fullName = info.name,
      avatarUrl = info.pictureUrl.map(_.toString),
      email = info.emailOpt.map(_.address),
      authMethod = AuthenticationMethod.OAuth2,
      oAuth2Info = Some(auth)
    )
    FacebookIdentity(socialUser)
  }
}

trait FacebookOAuthProvider extends OAuth2Support[FacebookIdentity] {

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
    with OAuth2ProviderHelper
    with Logging {

  def getIdentityId(token: OAuth2TokenInfo): Future[IdentityId] = getRichIdentity(token).map(RichIdentity.toIdentityId)

  def getRichIdentity(token: OAuth2TokenInfo): Future[FacebookIdentity] = {
    exchangeLongTermToken(token.accessToken).flatMap { oauthInfo =>
      getUserProfileInfo(oauthInfo.accessToken).map { info =>
        FacebookOAuthProvider.toIdentity(token, info)
      }
    }
  }

  private def getUserProfileInfo(accessToken: OAuth2AccessToken): Future[UserProfileInfo] = {
    WS.url(MeApi + accessToken.token).get() map { response =>
      log.info(s"[getUserProfileInfo] response=${response.body} status=${response.statusText}")
      val me = response.json
      (me \ Error).asOpt[JsObject] match {
        case Some(error) =>
          val message = (error \ Message).as[String]
          val errorType = (error \ Type).as[String]
          val exMsg = s"[getUserProfileInfo] error retrieving profile info from Facebook. errorType=$errorType, msg=$message"
          airbrake.notify(exMsg)
          throw new AuthException(exMsg, response)
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
            pictureUrl = avatarUrl,
            profileUrl = Some(s"http://facebook.com/$userId")
          )
      }
    }
  }

  private def exchangeLongTermToken(accessToken: OAuth2AccessToken): Future[OAuth2TokenInfo] = {
    val resF = WS.url(providerConfig.exchangeTokenUrl.get.toString).withQueryString(
      "grant_type" -> "fb_exchange_token",
      "client_id" -> providerConfig.clientId,
      "client_secret" -> providerConfig.clientSecret,
      "fb_exchange_token" -> accessToken.token
    ).get
    resF map { res =>
      log.info(s"[exchangeToken] response=${res.body}")
      if (res.status == Status.OK) {
        val params = res.body.split('&').map { token =>
          val nv = token.split('=')
          nv(0) -> nv(1)
        }.toMap
        OAuth2TokenInfo(accessToken = OAuth2AccessToken(params("access_token")), expiresIn = params.get("expires").map(_.toInt))
      } else throw new FacebookAPIError(res.status.toString, res.statusText)
    }
  }

  def buildTokenInfo(response: WSResponse): OAuth2TokenInfo = {
    try {
      log.info(s"[buildTokenInfo(${providerConfig.name})] response.body=${response.body}")
      val parsed = response.body.split("&").map { kv =>
        val p = kv.split("=").take(2)
        p(0) -> (if (p.length == 2) {
          try {
            JsNumber(p(1).toInt)
          } catch {
            case _: Throwable => JsString(p(1))
          }
        } else JsNull)
      }.toMap
      log.info(s"[buildTokenInfo] parsed=$parsed")
      OAuth2TokenInfo(
        OAuth2AccessToken(parsed.get(OAuth2Constants.AccessToken).map(_.as[String]).get),
        parsed.get(OAuth2Constants.TokenType).map(_.asOpt[String]).flatten,
        parsed.get(OAuth2Constants.ExpiresIn).map(_.asOpt[Int]).flatten,
        parsed.get(OAuth2Constants.RefreshToken).map(_.asOpt[String]).flatten
      )
    } catch {
      case t: Throwable =>
        throw new AuthException(s"[buildTokenInfo(${providerConfig.name}] Token process failure. status=${response.status}; body=${response.body}", response, t)
    }
  }
}

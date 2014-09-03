package com.keepit.social.providers

import com.keepit.social.UserIdentityProvider

import LinkedInProvider._
import play.api.libs.ws.{ Response, WS }
import play.api.{ Logger, Application }
import securesocial.core.{ OAuth2Info, IdentityId, AuthenticationException, SocialUser }
import play.api.libs.json.JsArray
import play.api.Play.current

/**
 * A LinkedIn Provider (OAuth2)
 */
class LinkedInProvider(application: Application)
    extends securesocial.core.OAuth2Provider(application) with UserIdentityProvider {

  override def id = LinkedInProvider.LinkedIn

  override protected def buildInfo(response: Response): OAuth2Info = {
    try super.buildInfo(response) catch {
      case e: Throwable =>
        Logger.info(s"[securesocial] Failed to build linkedin oauth2 info. Response was ${response.body}")
        throw e
    }
  }

  override def fillProfile(user: SocialUser): SocialUser = {
    val accessToken = user.oAuth2Info.get.accessToken
    val promise = WS.url(LinkedInProvider.Api + accessToken).withRequestTimeout(120000).get()

    try {
      val response = awaitResult(promise)
      val me = response.json
      (me \ ErrorCode).asOpt[Int] match {
        case Some(error) => {
          val message = (me \ Message).asOpt[String]
          val requestId = (me \ RequestId).asOpt[String]
          val timestamp = (me \ Timestamp).asOpt[String]
          Logger.error(
            "Error retrieving information from LinkedIn. Error code: %s, requestId: %s, message: %s, timestamp: %s"
              format (error, message, requestId, timestamp)
          )
          throw new AuthenticationException()
        }
        case _ => {
          val userId = (me \ Id).as[String]
          val firstName = (me \ FirstName).asOpt[String].getOrElse("")
          val lastName = (me \ LastName).asOpt[String].getOrElse("")
          val fullName = (me \ FormattedName).asOpt[String].getOrElse("")
          val emailAddress = (me \ EmailAddress).asOpt[String]
          val avatarUrl = (me \ PictureUrl \ "values").asOpt[JsArray].map(_(0).asOpt[String]).flatten

          SocialUser(user).copy(
            identityId = IdentityId(userId, id),
            firstName = firstName,
            lastName = lastName,
            email = emailAddress,
            fullName = fullName,
            avatarUrl = avatarUrl
          )
        }
      }
    } catch {
      case e: Exception => {
        Logger.error("[securesocial] error retrieving profile information from LinkedIn", e)
        throw new AuthenticationException()
      }
    }
  }
}

object LinkedInProvider {
  val Api = "https://api.linkedin.com/v1/people/~:(id,first-name,last-name,email-address,formatted-name,picture-urls::(original);secure=true)?format=json&oauth2_access_token="
  val LinkedIn = "linkedin"
  val ErrorCode = "errorCode"
  val Message = "message"
  val RequestId = "requestId"
  val Timestamp = "timestamp"
  val Id = "id"
  val FirstName = "firstName"
  val LastName = "lastName"
  val EmailAddress = "emailAddress"
  val FormattedName = "formattedName"
  val PictureUrl = "pictureUrls"
}

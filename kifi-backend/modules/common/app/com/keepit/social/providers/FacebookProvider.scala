package com.keepit.social.providers

import com.keepit.common.logging.Logging
import com.keepit.social.UserIdentityProvider
import play.api.libs.json.JsObject

import play.api.libs.ws.{ WS, WSResponse }
import play.api.{ Application }
import securesocial.core._

/**
 * A Facebook Provider
 */
class FacebookProvider(application: Application)
    extends securesocial.core.providers.FacebookProvider(application) with UserIdentityProvider with Logging {

  override protected def buildInfo(response: WSResponse): OAuth2Info = {
    try super.buildInfo(response) catch {
      case e: Throwable =>
        log.info(s"[securesocial] Failed to build oauth2 info. Response was ${response.body}")
        throw e
    }
  }

  override def fillProfile(user: SocialUser): SocialUser = {
    import play.api.Play.current
    val accessToken = user.oAuth2Info.get.accessToken
    val call = WS.url(MeApi + accessToken).get()
    try {
      val response = awaitResult(call) // method signature fixed so stuck with await for now
      log.info(s"[fillProfile] user=$user; response=${response.body} status=${response.statusText}")
      val me = response.json
      (me \ Error).asOpt[JsObject] match {
        case Some(error) =>
          val message = (error \ Message).as[String]
          val errorType = (error \ Type).as[String]
          log.error(s"[fillProfile] user=$user; error retrieving profile info from Facebook. errorType=$errorType, msg=$message")
          throw new AuthenticationException() // todo(ray): avoid using no-message AuthenticationException
        case _ =>
          val userId = (me \ Id).as[String]
          val name = (me \ Name).as[String]
          val firstName = (me \ FirstName).as[String]
          val lastName = (me \ LastName).as[String]
          val picture = (me \ Picture)
          val avatarUrl = (picture \ Data \ Url).asOpt[String]
          val email = (me \ Email).as[String]
          user.copy(
            identityId = IdentityId(userId, id),
            firstName = firstName,
            lastName = lastName,
            fullName = name,
            avatarUrl = avatarUrl,
            email = Some(email)
          )
      }
    } catch {
      case t: Throwable => {
        log.error(s"[fillProfile] error retrieving profile information from Facebook. exception=${t}; cause=${t.getCause}", t)
        throw new AuthenticationException()
      }
    }
  }

}

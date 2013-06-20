package com.keepit.common.social

import scala.concurrent.Future

import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.common.net.HttpClient
import com.keepit.model.{SocialUserInfoStates, SocialUserInfo}

import play.api.libs.json._
import play.api.libs.oauth.{RequestToken, OAuthCalculator}
import play.api.libs.ws.WS
import securesocial.core.SecureSocial

class LinkedInSocialGraph @Inject() (client: HttpClient) extends SocialGraph with Logging {

  val networkType = SocialNetworks.LINKEDIN

  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): Option[SocialUserRawInfo] = {
    val credentials = socialUserInfo.credentials.get
    val jsons = getJson(socialUserInfo)

    Some(SocialUserRawInfo(
      socialUserInfo.userId,
      socialUserInfo.id,
      SocialId(credentials.id.id),
      SocialNetworks.LINKEDIN,
      credentials.fullName,
      jsons))
  }

  def extractEmails(parentJson: JsValue): Seq[String] = (parentJson \ "emailAddress").asOpt[String].toSeq

  def extractFriends(parentJson: JsValue): Seq[(SocialUserInfo, JsValue)] =
    ((parentJson \ "values").asOpt[JsArray] getOrElse JsArray()).value collect {
      case jsv if (jsv \ "id").asOpt[String].exists(_ != "private") => createSocialUserInfo(jsv)
    }

  def sendMessage(from: SocialUserInfo, to: SocialUserInfo, subject: String, message: String) {
    val creds = from.credentials.get
    val info = creds.oAuth1Info.get
    val oauth = OAuthCalculator(SecureSocial.serviceInfoFor(creds).get.key, RequestToken(info.token, info.secret))
    WS.url(sendMessageUrl())
      .withHeaders("x-li-format" -> "json", "Content-Type" -> "application/json")
      .sign(oauth)
      .post(sendMessageBody(to.socialId, subject, message))
  }

  def revokePermissions(socialUserInfo: SocialUserInfo): Future[Unit] = {
    // LinkedIn has no way of doing this through the API
    Future.successful(())
  }

  private def sendMessageUrl(): String = {
    s"http://api.linkedin.com/v1/people/~/mailbox"
  }

  private def sendMessageBody(id: SocialId, subject: String, body: String): JsObject = Json.obj(
    "recipients" -> Json.obj("values" -> Json.arr(
      Json.obj("person" -> Json.obj("_path" -> s"/people/$id"))
    )),
    "subject" -> subject,
    "body" -> body
  )

  private def connectionsUrl(id: SocialId): String = {
    s"http://api.linkedin.com/v1/people/$id/connections?format=json"
  }

  private def profileUrl(id: SocialId): String = {
    s"http://api.linkedin.com/v1/people/$id:(id,firstName,lastName,emailAddress,pictureUrl)?format=json"
  }

  private def getJson(socialUserInfo: SocialUserInfo): Seq[JsValue] = {
    val creds = socialUserInfo.credentials.get
    val info = creds.oAuth1Info.get
    val sid = socialUserInfo.socialId
    val oauth = OAuthCalculator(SecureSocial.serviceInfoFor(creds).get.key, RequestToken(info.token, info.secret))
    for (url <- Seq(connectionsUrl(sid), profileUrl(sid))) yield {
      val signedUrl = oauth.sign(url)
      client.longTimeout().get(signedUrl).json
    }
  }

  private def createSocialUserInfo(friend: JsValue): (SocialUserInfo, JsValue) =
    (SocialUserInfo(
      fullName = ((friend \ "firstName").asOpt[String] ++ (friend \ "lastName").asOpt[String]).mkString(" "),
      pictureUrl = (friend \ "pictureUrl").asOpt[String],
      socialId = SocialId((friend \ "id").as[String]),
      networkType = SocialNetworks.LINKEDIN,
      state = SocialUserInfoStates.FETCHED_USING_FRIEND
    ), friend)
}

package com.keepit.common.social

import scala.concurrent.Future

import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.common.net.HttpClient
import com.keepit.model.{SocialUserInfoRepo, SocialUserInfoStates, SocialUserInfo}
import com.keepit.social.{SocialUserRawInfo, SocialNetworks, SocialId, SocialGraph}

import play.api.libs.json._
import play.api.libs.ws.WS
import com.keepit.common.db.slick.Database

class LinkedInSocialGraph @Inject() (
    client: HttpClient,
    db: Database,
    socialRepo: SocialUserInfoRepo
  ) extends SocialGraph with Logging {

  val networkType = SocialNetworks.LINKEDIN

  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): Option[SocialUserRawInfo] = {
    val credentials = socialUserInfo.credentials.get
    val jsons = if (credentials.oAuth2Info.isDefined) {
      getJson(socialUserInfo)
    } else {
      log.warn(s"LinkedIn app not authorized with OAuth2 for $socialUserInfo; not fetching connections.")
      db.readWrite { implicit s =>
        socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.APP_NOT_AUTHORIZED).withLastGraphRefresh())
      }
      Seq()
    }

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
    WS.url(sendMessageUrl(getAccessToken(from)))
      .withHeaders("x-li-format" -> "json", "Content-Type" -> "application/json")
      .post(sendMessageBody(to.socialId, subject, message))
  }

  def revokePermissions(socialUserInfo: SocialUserInfo): Future[Unit] = {
    // LinkedIn has no way of doing this through the API
    Future.successful(())
  }

  private def sendMessageUrl(accessToken: String): String = {
    s"https://api.linkedin.com/v1/people/~/mailbox?oauth2_access_token=$accessToken"
  }

  private def sendMessageBody(id: SocialId, subject: String, body: String): JsObject = Json.obj(
    "recipients" -> Json.obj("values" -> Json.arr(
      Json.obj("person" -> Json.obj("_path" -> s"/people/$id"))
    )),
    "subject" -> subject,
    "body" -> body
  )

  private def connectionsUrl(id: SocialId, accessToken: String): String = {
    s"https://api.linkedin.com/v1/people/$id/connections:(id,firstName,lastName,pictureUrl,publicProfileUrl)?format=json&oauth2_access_token=$accessToken"
  }

  private def profileUrl(id: SocialId, accessToken: String): String = {
    s"https://api.linkedin.com/v1/people/$id:(id,firstName,lastName,emailAddress,pictureUrl)?format=json&oauth2_access_token=$accessToken"
  }

  private def getJson(socialUserInfo: SocialUserInfo): Seq[JsValue] = {
    val token = getAccessToken(socialUserInfo)
    val sid = socialUserInfo.socialId
    for (url <- Seq(connectionsUrl(sid, token), profileUrl(sid, token))) yield {
      client.longTimeout().get(url).json
    }
  }

  private def createSocialUserInfo(friend: JsValue): (SocialUserInfo, JsValue) =
    (SocialUserInfo(
      fullName = ((friend \ "firstName").asOpt[String] ++ (friend \ "lastName").asOpt[String]).mkString(" "),
      pictureUrl = (friend \ "pictureUrl").asOpt[String],
      profileUrl = (friend \ "publicProfileUrl").asOpt[String],
      socialId = SocialId((friend \ "id").as[String]),
      networkType = SocialNetworks.LINKEDIN,
      state = SocialUserInfoStates.FETCHED_USING_FRIEND
    ), friend)

  protected def getAccessToken(socialUserInfo: SocialUserInfo): String = {
    val credentials = socialUserInfo.credentials.getOrElse(throw new Exception("Can't find credentials for %s".format(socialUserInfo)))
    val oAuth2Info = credentials.oAuth2Info.getOrElse(throw new Exception("Can't find oAuth2Info for %s".format(socialUserInfo)))
    oAuth2Info.accessToken
  }
}

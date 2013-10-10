package com.keepit.common.social

import scala.concurrent.Future

import com.google.inject.Inject
import com.keepit.common.db.State
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.net.{NonOKResponseException, HttpClient}
import com.keepit.model.SocialUserInfoStates._
import com.keepit.model.{SocialUserInfoRepo, SocialUserInfoStates, SocialUserInfo}
import com.keepit.social.{SocialUserRawInfo, SocialNetworks, SocialId, SocialGraph}

import play.api.http.Status._
import play.api.libs.json._
import play.api.libs.ws.WS

object LinkedInSocialGraph {
  val ProfileFields = Seq("id","firstName","lastName","pictureUrl","publicProfileUrl")
  val ProfileFieldSelector = ProfileFields.mkString("(",",",")")
  val ConnectionsPageSize = 500
}

class LinkedInSocialGraph @Inject() (
    client: HttpClient,
    db: Database,
    socialRepo: SocialUserInfoRepo
  ) extends SocialGraph with Logging {

  import LinkedInSocialGraph.ProfileFieldSelector

  val networkType = SocialNetworks.LINKEDIN

  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): Option[SocialUserRawInfo] = {
    val credentials = socialUserInfo.credentials.get

    def fail(msg: String, state: State[SocialUserInfo] = FETCH_FAIL) {
      log.warn(msg)
      db.readWrite { implicit s =>
        socialRepo.save(socialUserInfo.withState(state).withLastGraphRefresh())
      }
    }

    val jsonsOpt = if (credentials.oAuth2Info.isDefined) {
      try {
        Some(getJson(socialUserInfo))
      } catch {
        case e @ NonOKResponseException(url, response, _) =>
          if (response.status == UNAUTHORIZED) {
            fail(s"LinkedIn account $socialUserInfo is unauthorized. Response: ${response.json}", APP_NOT_AUTHORIZED)
            None
          } else {
            fail(s"Error fetching LinkedIn connections for $socialUserInfo. Response: ${response.json}")
            throw e
          }
      }
    } else {
      log.warn(s"LinkedIn app not authorized with OAuth2 for $socialUserInfo; not fetching connections.")
      db.readWrite { implicit s =>
        socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.APP_NOT_AUTHORIZED).withLastGraphRefresh())
      }
      None
    }

    jsonsOpt map {
      SocialUserRawInfo(
        socialUserInfo.userId,
        socialUserInfo.id,
        SocialId(credentials.identityId.userId),
        SocialNetworks.LINKEDIN,
        credentials.fullName,
        _)
    }
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

  private def connectionsUrl(id: SocialId, accessToken: String, start: Int, count: Int): String = {
    s"https://api.linkedin.com/v1/people/$id/connections:$ProfileFieldSelector?format=json" +
        s"&start=$start&count=$count&oauth2_access_token=$accessToken"
  }

  private def profileUrl(id: SocialId, accessToken: String): String = {
    s"https://api.linkedin.com/v1/people/$id:$ProfileFieldSelector?format=json&oauth2_access_token=$accessToken"
  }

  def updateSocialUserInfo(sui: SocialUserInfo, json: JsValue): SocialUserInfo = {
    (json \ "id").asOpt[String] map { id =>
      assert(sui.socialId.id == id, s"Social id in profile $id should be equal to the existing id ${sui.socialId}")
      sui.copy(
        fullName = ((json \ "firstName").asOpt[String] ++ (json \ "lastName").asOpt[String]).mkString(" "),
        pictureUrl = (json \ "pictureUrl").asOpt[String] orElse sui.pictureUrl,
        profileUrl = (json \ "publicProfileUrl").asOpt[String] orElse sui.profileUrl
      )
    } getOrElse sui
  }

  val TWO_MINUTES = 2 * 60 * 1000
  private def getJson(url: String): JsValue = client.withTimeout(TWO_MINUTES).get(url, client.ignoreFailure).json

  private def getJson(socialUserInfo: SocialUserInfo): Seq[JsValue] = {
    import LinkedInSocialGraph.{ConnectionsPageSize => PageSize}
    val token = getAccessToken(socialUserInfo)
    val sid = socialUserInfo.socialId
    val connectionsPages = Stream.from(0).map { n => getJson(connectionsUrl(sid, token, n*PageSize, PageSize)) }
    val numPages = 1 + connectionsPages.indexWhere(json => (json \ "_count").asOpt[Int].getOrElse(0) < PageSize)
    connectionsPages.take(numPages).toIndexedSeq :+ getJson(profileUrl(sid, token))
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

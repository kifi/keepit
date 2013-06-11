package com.keepit.common.social

import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.common.net.HttpClient
import com.keepit.model.{SocialUserInfoStates, SocialUserInfo}

import play.api.libs.json._
import play.api.libs.oauth.{RequestToken, OAuthCalculator}
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

  private def connectionsUrl(id: SocialId): String = {
    s"http://api.linkedin.com/v1/people/$id/connections?format=json"
  }

  private def profileUrl(id: SocialId): String = {
    s"http://api.linkedin.com/v1/people/$id:(id,firstName,lastName,emailAddress)?format=json"
  }

  private def getJson(socialUserInfo: SocialUserInfo): Seq[JsValue] = {
    val creds = socialUserInfo.credentials.get
    val info = creds.oAuth1Info.get
    val sid = socialUserInfo.socialId
    for (url <- Seq(connectionsUrl(sid), profileUrl(sid))) yield {
      val signedUrl = OAuthCalculator(SecureSocial.serviceInfoFor(creds).get.key,
        RequestToken(info.token, info.secret)).sign(url)
      client.get(signedUrl).json
    }
  }

  private def createSocialUserInfo(friend: JsValue): (SocialUserInfo, JsValue) =
    (SocialUserInfo(
      fullName = ((friend \ "firstName").asOpt[String] ++ (friend \ "lastName").asOpt[String]).mkString(" "),
      socialId = SocialId((friend \ "id").as[String]),
      networkType = SocialNetworks.LINKEDIN,
      state = SocialUserInfoStates.FETCHED_USING_FRIEND
    ), friend)
}

package com.keepit.common.social

import com.keepit.common.net.HttpClient
import com.keepit.common.logging.Logging
import com.keepit.model.{User, SocialUserInfo}
import com.google.inject.Inject
import play.api.libs.json._

object FacebookSocialGraph {
  val FULL_PROFILE = "link,name,first_name,middle_name,last_name,location,locale,gender,username,languages,third_party_id,installed,timezone,updated_time,verified,bio,birthday,devices,education,email,picture,significant_other,website,work"
}

class FacebookSocialGraph @Inject() (httpClient: HttpClient) extends Logging {

  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): SocialUserRawInfo = {
    val jsons = fetchJsons(url(socialUserInfo.socialId, getAccessToken(socialUserInfo)))
    val json = jsons.head
    SocialUserRawInfo(
        socialUserInfo.userId,
        socialUserInfo.id,
        SocialId((json \ "username").asOpt[String].getOrElse((json \ "id").as[String])),
        SocialNetworks.FACEBOOK,
        (json \ "name").asOpt[String].getOrElse(socialUserInfo.fullName),
        jsons)
  }

  def getAccessToken(socialUserInfo: SocialUserInfo): String = {
    val credentials = socialUserInfo.credentials.getOrElse(throw new Exception("Can't find credentials for %s".format(socialUserInfo)))
    val oAuth2Info = credentials.oAuth2Info.getOrElse(throw new Exception("Can't find oAuth2Info for %s".format(socialUserInfo)))
    oAuth2Info.accessToken
  }

  private def fetchJsons(url: String): List[JsValue] = {
    val jsons = get(url)
    nextPageUrl(jsons) match {
      case None => List(jsons)
      case Some(nextUrl) => jsons :: fetchJsons(nextUrl)
    }
  }

  def nextPageUrl(json: JsValue): Option[String] = (json \ "friends" \ "paging" \ "next").asOpt[String]

  private def get(url: String): JsValue = httpClient.longTimeout.get(url).json

  private def url(id: SocialId, accessToken: String) = "https://graph.facebook.com/%s?access_token=%s&fields=%s,friends.fields(%s)".format(
      id.id, accessToken, FacebookSocialGraph.FULL_PROFILE, FacebookSocialGraph.FULL_PROFILE)
}

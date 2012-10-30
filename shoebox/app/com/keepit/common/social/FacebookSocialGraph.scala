package com.keepit.common.social

import com.keepit.common.net.HttpClient
import com.keepit.model.{User, SocialUserInfo}
import com.google.inject.Inject
import securesocial.core.java.SocialUser
import play.api.libs.json._

object FacebookSocialGraph {
  val FULL_PROFILE = "link,name,first_name,middle_name,last_name,location,locale,gender,username,languages,third_party_id,installed,timezone,updated_time,verified,bio,birthday,devices,education,email,picture,significant_other,website,work" 
}

class FacebookSocialGraph @Inject() (httpClient: HttpClient) {
  
  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): SocialUserRawInfo = {
    val jsons = fetchJsons(socialUserInfo)
    SocialUserRawInfo(
        socialUserInfo.userId, 
        socialUserInfo.id, 
        SocialId((jsons.head \ "username").as[String]), 
        SocialNetworks.FACEBOOK, 
        (jsons.head \ "name").asOpt[String].getOrElse(socialUserInfo.fullName), 
        jsons)
  }
  
  private def getAccessToken(socialUserInfo: SocialUserInfo): String = {
    val credentials = socialUserInfo.credentials.getOrElse(throw new Exception("Can't find credentials for %s".format(socialUserInfo)))
    val oAuth2Info = credentials.oAuth2Info.getOrElse(throw new Exception("Can't find oAuth2Info for %s".format(socialUserInfo)))
    oAuth2Info.accessToken
  }
  
  private def fetchJsons(socialUserInfo: SocialUserInfo): Seq[JsValue] = {
    Seq(fetchJson(socialUserInfo))
  }
  
  private def fetchJson(socialUserInfo: SocialUserInfo): JsValue = {
    httpClient.longTimeout.get(url(socialUserInfo.socialId, getAccessToken(socialUserInfo))).json
  }
  
  private def url(id: SocialId, accessToken: String) = "https://graph.facebook.com/%s?access_token=%s&fields=%s,friends.fields(%s)".format(
      id.id, accessToken, FacebookSocialGraph.FULL_PROFILE, FacebookSocialGraph.FULL_PROFILE)
}
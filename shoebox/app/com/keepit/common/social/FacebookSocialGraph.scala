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
    val json = fetchJson(socialUserInfo)
    SocialUserRawInfo(
        socialUserInfo.userId, 
        socialUserInfo.id, 
        SocialId((json \ "username").as[String]), 
        SocialNetworks.FACEBOOK, 
        (json \ "name").asOpt[String].getOrElse(socialUserInfo.fullName), 
        json)
  }
  
  def getAccessToken(socialUserInfo: SocialUserInfo): String = {
    val credentials = socialUserInfo.credentials.get
    val oAuth2Info = credentials.oAuth2Info.get
    oAuth2Info.accessToken
  }
  
  def fetchJson(socialUserInfo: SocialUserInfo): JsValue = httpClient.get(url(socialUserInfo.socialId, getAccessToken(socialUserInfo))).json
  
  def url(id: SocialId, accessToken: String) = "https://graph.facebook.com/%s?access_token=%s&fields=%s,friends.fields(%s)".format(
      id.id, accessToken, FacebookSocialGraph.FULL_PROFILE, FacebookSocialGraph.FULL_PROFILE)
}
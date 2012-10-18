package com.keepit.common.social

import com.keepit.common.net.HttpClient
import com.keepit.model.{FacebookId, User, SocialId}
import com.google.inject.Inject
import securesocial.core.java.SocialUser

object FacebookSocialGraph {
  val FULL_PROFILE = "link,name,first_name,middle_name,last_name,location,locale,gender,username,languages,third_party_id,installed,timezone,updated_time,verified,bio,birthday,devices,education,email,picture,significant_other,website,work" 
}

class FacebookSocialGraph @Inject() (httpClient: HttpClient) {
  
  def fetchJson(user: User): SocialUserRawInfo = {
    val oAuth2Info = user.socialUser.get.oAuth2Info.get
    val accessToken = oAuth2Info.accessToken
    val json = httpClient.get(url(SocialId(user.facebookId.value), accessToken)).json
    SocialUserRawInfo(user.id, SocialId((json \ "username").as[String]), SocialNetworks.FACEBOOK, (json \ "name").asOpt[String].getOrElse("%s %s".format(user.firstName, user.lastName)), json)
  } 
  
  def url(id: SocialId, accessToken: String) = "https://graph.facebook.com/%s?access_token=%s&fields=%s,friends.fields(%s)".format(
      id.id, accessToken, FacebookSocialGraph.FULL_PROFILE, FacebookSocialGraph.FULL_PROFILE)
}
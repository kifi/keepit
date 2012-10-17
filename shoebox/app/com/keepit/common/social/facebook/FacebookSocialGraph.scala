package com.keepit.common.social.facebook

import com.keepit.common.net.HttpClient
import com.keepit.model.{FacebookId, User}
import com.google.inject.Inject
import securesocial.core.java.SocialUser

object FacebookSocialGraph {
  val FULL_PROFILE = "link,name,first_name,middle_name,last_name,location,locale,gender,username,languages,third_party_id,installed,timezone,updated_time,verified,bio,birthday,devices,education,email,picture,significant_other,website,work" 
}

class FacebookSocialGraph @Inject() (httpClient: HttpClient) {
  
  def fetchJson(user: User) = {
    val oAuth2Info = user.socialUser.get.oAuth2Info.get
    val accessToken = oAuth2Info.accessToken
    httpClient.get(url(user.facebookId, accessToken)).json
  } 
  
  def url(facebookId: FacebookId, accessToken: String) = "https://graph.facebook.com/%s?access_token=%s&fields=%s,friends.fields(%s)".format(
      facebookId.value, accessToken, FacebookSocialGraph.FULL_PROFILE, FacebookSocialGraph.FULL_PROFILE)
}
package com.keepit.serializer

import org.specs2.mutable._

import securesocial.core._

class SocialUserSerializerTest extends Specification {

  "SocialUserSerializer" should {
    "do a basic serialization flow" in {
      val oAuth2Info = OAuth2Info(accessToken = "AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD",
          tokenType = Some("FB"), expiresIn = Some(1234), refreshToken = Some("RF"))
      val user = SocialUser(UserId("myFbId", "facebook"), "Dandrew", "Conner", "Dandrew Conner", Some("andrew@42.com"),
          Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None, Some(oAuth2Info), None)
      val serializer = SocialUserSerializer.userSerializer
      val json = serializer.writes(user)
      println(json)
      val newUser = serializer.reads(json).get
      user === newUser
    }
  }

}

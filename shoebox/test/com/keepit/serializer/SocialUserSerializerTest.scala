package com.keepit.serializer

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import securesocial.core._

@RunWith(classOf[JUnitRunner])
class SocialUserSerializerTest extends SpecificationWithJUnit {

  "SocialUserSerializer" should {
    "do a basic serialization flow" in {
      val oAuth2Info = OAuth2Info("abcd", Some("FB"), Some(1234), Some("RF"))
      val user = SocialUser(UserId("myFbId", "facebook"), "eishay", Some("eishay@42.com"), 
          Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, true, None, Some(oAuth2Info), None)
      val serializer = new SocialUserSerializer()
      val json = serializer.writes(user)
      println(json)
      val newUser = serializer.reads(json)
      user === newUser
    }
  }

}
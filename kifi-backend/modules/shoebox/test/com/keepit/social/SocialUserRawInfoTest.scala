package com.keepit.social

import com.keepit.common.db.Id
import org.specs2.mutable.Specification
import play.api.libs.json.{ Json, JsNull }

class SocialUserRawInfoTest extends Specification {

  "SocialUserRawInfo" should {
    "do a basic serialization flow" in {

      val info = SocialUserRawInfo(Some(Id(1)), Some(Id(2)), SocialId("leo_grimaldi"), SocialNetworks.TWITTER, "Léo Grimaldi", Stream(Json.obj("nevermind" -> "whatever")))
      val json = SocialUserRawInfo.format.writes(info)
      val newInfo = SocialUserRawInfo.format.reads(json).get
      info === newInfo
    }
  }

}

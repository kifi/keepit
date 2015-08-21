package com.keepit.notify.model

import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.notify.model.event.{DepressedRobotGrumble, NewSocialConnection}
import org.specs2.mutable.Specification
import play.api.libs.json.Json

class NotificationKindTest extends Specification {

  "NotificationKind" should {

    "serialize and deserialize to json with event properly" in {

      val event = DepressedRobotGrumble(Recipient(Id[User](1)), currentDateTime, "marvin", "life, the universe, and everything")

      val str = Json.stringify(Json.toJson(event))

      val unStr = Json.parse(str).as[DepressedRobotGrumble]

      event === unStr

    }

    "fail when deserializing the wrong kind" in {

      val event = DepressedRobotGrumble(Recipient(Id[User](1)), currentDateTime, "marvin", "life, the universe, and everything")

      val str = Json.stringify(Json.toJson(event))

      val unStr = Json.parse(str).validate[NewSocialConnection]

      unStr.isError === true

    }

  }

}

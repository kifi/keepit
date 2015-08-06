package com.keepit.notify.model

import com.keepit.common.db.Id
import com.keepit.common.time._
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.libs.json.Json


class NotificationKindTest extends Specification {

  "NotificationKind" should {

    "serialize and deserialize to json with event properly" in {

      val event = NewFollower(Id(1), currentDateTime, Id(1), Id(1))

      val str = Json.stringify(Json.toJson(event))

      val unStr = Json.parse(str).as[NewFollower]

      event === unStr

    }

    "fail when deserializing the wrong kind" in {

      val event = NewFollower(Id(1), currentDateTime, Id(1), Id(1))

      val str = Json.stringify(Json.toJson(event))

      val unStr = Json.parse(str).validate[NewCollaborator]

      unStr.isError === true

    }

  }

}

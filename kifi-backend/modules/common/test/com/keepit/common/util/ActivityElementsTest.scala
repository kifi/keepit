package com.keepit.common.util

import com.keepit.common.db.ExternalId
import com.keepit.common.json.TestHelper
import com.keepit.common.path.Path
import com.keepit.model.User
import org.specs2.mutable.Specification
import play.api.libs.json.Json

class ActivityElementsTest extends Specification {
  "ActivityElements" should {
    "format properly" in {
      val text = ActivityElement.TextElement("hey")
      val user = ActivityElement.UserElement(ExternalId[User](), "Jimmy", "0.jpg", Path("jimmy"))
      TestHelper.deepCompare(Json.toJson(text)(ActivityElement.writes), Json.obj("text" -> text.text, "kind" -> ActivityElement.TextElement.kind)) must beNone
      TestHelper.deepCompare(Json.toJson(user), Json.obj("id" -> user.id, "text" -> user.name, "image" -> user.image, "url" -> user.path.relative, "kind" -> ActivityElement.UserElement.kind)) must beNone
      1 === 1
    }
  }
}

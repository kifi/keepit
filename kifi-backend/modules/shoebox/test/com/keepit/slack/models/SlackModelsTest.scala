package com.keepit.slack.models

import com.keepit.model.OrganizationSettings
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsSuccess, Json }

class SlackModelsTest extends Specification {
  implicit val format = OrganizationSettings.dbFormat
  "SlackModels" should {
    "deserialize valid inputs" in {
      "identify" in {
        Json.parse(
          """
            |{
            |  "ok": true,
            |  "url": "https://kifi.slack.com/",
            |  "team": "Kifi",
            |  "user": "ryanpbrewster",
            |  "team_id": "T02A81H50",
            |  "user_id": "U054D149J"
            |}
          """.stripMargin).validate[SlackIdentifyResponse] must haveClass[JsSuccess[_]]
      }
    }
  }
}

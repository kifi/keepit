package com.keepit.slack.models

import com.keepit.model.OrganizationSettings
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsError, JsSuccess, Json }

class SlackModelsTest extends Specification {
  implicit val format = OrganizationSettings.dbFormat
  "SlackModels" should {
    "deserialize valid inputs" in {
      "auth.test" in {
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
      "users.list" in {
        val raw = """{"ok":true,"members":[{"id":"U0JT6K0KV","team_id":"T0JQ45PPS","name":"jarod","deleted":false,"status":null,"color":"4bbe2e","real_name":"","tz":"America/Los_Angeles","tz_label":"Pacific Standard Time","tz_offset":-28800,"profile":{"avatar_hash":"gb8cb5a6ee82","real_name":"","real_name_normalized":"","email":"jarod@lvsweet.com","image_24":"https://secure.gravatar.com/avatar/b8cb5a6ee827f195f7495832dcabb37c.jpg?s=24&d=https%3A%2F%2Fslack.global.ssl.fastly.net%2F0180%2Fimg%2Favatars%2Fava_0025-24.png","image_32":"https://secure.gravatar.com/avatar/b8cb5a6ee827f195f7495832dcabb37c.jpg?s=32&d=https%3A%2F%2Fslack.global.ssl.fastly.net%2F66f9%2Fimg%2Favatars%2Fava_0025-32.png","image_48":"https://secure.gravatar.com/avatar/b8cb5a6ee827f195f7495832dcabb37c.jpg?s=48&d=https%3A%2F%2Fslack.global.ssl.fastly.net%2F66f9%2Fimg%2Favatars%2Fava_0025-48.png","image_72":"https://secure.gravatar.com/avatar/b8cb5a6ee827f195f7495832dcabb37c.jpg?s=72&d=https%3A%2F%2Fslack.global.ssl.fastly.net%2F66f9%2Fimg%2Favatars%2Fava_0025-72.png","image_192":"https://secure.gravatar.com/avatar/b8cb5a6ee827f195f7495832dcabb37c.jpg?s=192&d=https%3A%2F%2Fslack.global.ssl.fastly.net%2F7fa9%2Fimg%2Favatars%2Fava_0025-192.png","image_512":"https://secure.gravatar.com/avatar/b8cb5a6ee827f195f7495832dcabb37c.jpg?s=512&d=https%3A%2F%2Fslack.global.ssl.fastly.net%2F7fa9%2Fimg%2Favatars%2Fava_0025-512.png"},"is_admin":false,"is_owner":false,"is_primary_owner":false,"is_restricted":false,"is_ultra_restricted":false,"is_bot":false,"has_2fa":false},{"id":"U0JQ3ETKR","team_id":"T0JQ45PPS","name":"mgreetzelle","deleted":false,"status":null,"color":"9f69e7","real_name":"","tz":"America/Los_Angeles","tz_label":"Pacific Standard Time","tz_offset":-28800,"profile":{"avatar_hash":"g7e026919f4f","real_name":"","real_name_normalized":"","email":"immollybloom@gmail.com","image_24":"https://secure.gravatar.com/avatar/7e026919f4fc91bcc9e38be7af8bebcd.jpg?s=24&d=https%3A%2F%2Fslack.global.ssl.fastly.net%2F66f9%2Fimg%2Favatars%2Fava_0013-24.png","image_32":"https://secure.gravatar.com/avatar/7e026919f4fc91bcc9e38be7af8bebcd.jpg?s=32&d=https%3A%2F%2Fslack.global.ssl.fastly.net%2F0180%2Fimg%2Favatars%2Fava_0013-32.png","image_48":"https://secure.gravatar.com/avatar/7e026919f4fc91bcc9e38be7af8bebcd.jpg?s=48&d=https%3A%2F%2Fslack.global.ssl.fastly.net%2F66f9%2Fimg%2Favatars%2Fava_0013-48.png","image_72":"https://secure.gravatar.com/avatar/7e026919f4fc91bcc9e38be7af8bebcd.jpg?s=72&d=https%3A%2F%2Fslack.global.ssl.fastly.net%2F66f9%2Fimg%2Favatars%2Fava_0013-72.png","image_192":"https://secure.gravatar.com/avatar/7e026919f4fc91bcc9e38be7af8bebcd.jpg?s=192&d=https%3A%2F%2Fslack.global.ssl.fastly.net%2F7fa9%2Fimg%2Favatars%2Fava_0013-192.png","image_512":"https://secure.gravatar.com/avatar/7e026919f4fc91bcc9e38be7af8bebcd.jpg?s=512&d=https%3A%2F%2Fslack.global.ssl.fastly.net%2F7fa9%2Fimg%2Favatars%2Fava_0013-512.png"},"is_admin":true,"is_owner":true,"is_primary_owner":true,"is_restricted":false,"is_ultra_restricted":false,"is_bot":false},{"id":"USLACKBOT","team_id":"T0JQ45PPS","name":"slackbot","deleted":false,"status":null,"color":"757575","real_name":"slackbot","tz":null,"tz_label":"Pacific Standard Time","tz_offset":-28800,"profile":{"first_name":"slackbot","last_name":"","image_24":"https://slack.global.ssl.fastly.net/0180/img/slackbot_24.png","image_32":"https://slack.global.ssl.fastly.net/66f9/img/slackbot_32.png","image_48":"https://slack.global.ssl.fastly.net/66f9/img/slackbot_48.png","image_72":"https://slack.global.ssl.fastly.net/0180/img/slackbot_72.png","image_192":"https://slack.global.ssl.fastly.net/66f9/img/slackbot_192.png","image_512":"https://slack.global.ssl.fastly.net/7fa9/img/slackbot_512.png","avatar_hash":"sv1444671949","real_name":"slackbot","real_name_normalized":"slackbot","email":null,"fields":null},"is_admin":false,"is_owner":false,"is_primary_owner":false,"is_restricted":false,"is_ultra_restricted":false,"is_bot":false}],"cache_ts":1455754717}"""
        val res = (Json.parse(raw) \ "members").validate[Seq[SlackUserInfo]]
        res match {
          case e: JsError => throw new Exception(e.errors.mkString)
          case _ =>
        }
        res must haveClass[JsSuccess[_]]
      }
      "auth.access" in {
        Json.parse( // In principle, posting my personal Slack access token is a bad idea, but I like to live dangerously
          """
            |{
            |  "ok": true,
            |  "access_token": "xoxp-2348051170-5149038324-14293481814-ab9b6155e2",
            |  "scope": "read,identify,post,incoming-webhook,search:read,channels:write",
            |  "team_name": "Kifi",
            |  "team_id": "T02A81H50"
            |}
          """.stripMargin).validate[SlackAuthorizationResponse] must haveClass[JsSuccess[_]]
      }
      "search.messages" in {
        Json.parse(
          """
            |{
            |    "ok": true,
            |    "query": "in:#isthispandatime",
            |    "messages": {
            |        "total": 1,
            |        "pagination": {
            |            "total_count": 1,
            |            "page": 1,
            |            "per_page": 20,
            |            "page_count": 1,
            |            "first": 1,
            |            "last": 1
            |        },
            |        "paging": {
            |            "count": 20,
            |            "total": 1,
            |            "page": 1,
            |            "pages": 1
            |        },
            |        "matches": [
            |            {
            |                "type": "message",
            |                "user": "U054D149J",
            |                "username": "ryanpbrewster",
            |                "ts": "1447265230.000005",
            |                "text": "asdf",
            |                "channel": {
            |                    "id": "C0EAK0545",
            |                    "name": "isthispandatime"
            |                },
            |                "permalink": "https://kifi.slack.com/archives/isthispandatime/p1447265230000005"
            |            }
            |        ]
            |    }
            |}
          """.stripMargin).validate[SlackSearchResponse] must haveClass[JsSuccess[_]]
      }
    }
  }
}

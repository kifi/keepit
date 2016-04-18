package com.keepit.slack.models

import java.io.File

import com.keepit.common.strings._
import com.keepit.common.time._
import com.keepit.model.OrganizationSettings
import org.joda.time.DateTime
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
      "presence away" in {
        val res = Json.parse(
          """
            |{
            |    "ok": true,
            |    "presence": "away"
            |}
          """.stripMargin).validate[SlackUserPresence].get
        res.state === SlackUserPresenceState.Away
        res.lastActivity === None
      }
      "presence active" in {
        val res = Json.parse(
          """
            |{
            |    "ok": true,
            |    "presence": "active",
            |    "online": true,
            |    "auto_away": false,
            |    "manual_away": false,
            |    "connection_count": 1,
            |    "last_activity": 1457138486
            |}
          """.stripMargin).validate[SlackUserPresence].get
        res.state === SlackUserPresenceState.Active
        res.lastActivity === Some(new DateTime(1457138486L, DEFAULT_DATE_TIME_ZONE))
      }
      "presence error" in {
        val res = Json.parse(
          """
            |{
            |    "ok": false,
            |    "error": "user_not_found"
            |}
          """.stripMargin).validate[SlackUserPresence].get
        res.state === SlackUserPresenceState.Unknown
        res.lastActivity === None
      }
      "users.list" in {
        val raw = io.Source.fromFile(new File("test/data/slack_kifi.json"), UTF8).mkString
        val res = (Json.parse(raw) \ "members").validate[Seq[FullSlackUserInfo]]
        res match {
          case e: JsError => throw new Exception(e.errors.mkString)
          case JsSuccess(users, _) =>
            users.size === 69
            users.filter(_.bot).size === 8
            users.filter(_.deleted).size === 44
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

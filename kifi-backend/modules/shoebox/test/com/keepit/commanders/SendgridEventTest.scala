package com.keepit.commanders

import org.specs2.mutable.Specification
import play.api.libs.json.{ JsError, JsSuccess, Json }
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.db.ExternalId

class SendgridEventTest extends Specification {

  "SendgridEvent" should {

    "parse test from sendgrid" in {
      val rawTestJson =
        """[
        {
          "email":"john.doe@sendgrid.com",
          "sg_event_id":"VzcPxPv7SdWvUugt-xKymw",
          "sg_message_id":"142d9f3f351.7618.254f56.filter-147.22649.52A663508.0",
          "timestamp":1386636112,
          "smtp-id":"<142d9f3f351.7618.254f56@sendgrid.com>",
          "event":"processed",
          "category":["category1","category2","category3"],
          "id":"001",
          "purchase":"PO1452297845",
          "uid":"123456"
        },
        {
          "email":"not an email address",
          "smtp-id":"<4FB29F5D.5080404@sendgrid.com>",
          "timestamp":1386636115,
          "reason":"Invalid",
          "event":"dropped",
          "category":["category1","category2","category3"],
          "id":"001",
          "purchase":"PO1452297845",
          "uid":"123456"},
        {
          "email":"john.doe@sendgrid.com",
          "sg_event_id":"vZL1Dhx34srS-HkO-gTXBLg",
          "sg_message_id":"142d9f3f351.7618.254f56.filter-147.22649.52A663508.0",
          "timestamp":1386636113,
          "smtp-id":"<142d9f3f351.7618.254f56@sendgrid.com>",
          "event":"delivered",
          "category":["category1","category2","category3"],
          "id":"001",
          "purchase":"PO1452297845",
          "uid":"123456"
        },
        {
          "email":"john.smith@sendgrid.com",
          "timestamp":1386636127,
          "uid":"123456",
          "ip":"174.127.33.234",
          "purchase":"PO1452297845",
          "useragent":"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.63 Safari/537.36",
          "id":"001",
          "category":["category1","category2","category3"],
          "event":"open"
        },
        {
          "uid":"123456",
          "ip":"174.56.33.234",
          "purchase":"PO1452297845",
          "useragent":"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.63 Safari/537.36",
          "event":"click",
          "email":"john.doe@sendgrid.com",
          "timestamp":1386637216,
          "url":"http://www.google.com/",
          "category":["category1","category2","category3"],
          "id":"001"
        },
        {
          "uid":"123456",
          "status":"5.1.1",
          "sg_event_id":"X_C_clhwSIi4EStEpol-SQ",
          "reason":"550 5.1.1 The email account that you tried to reach does not exist. Please try double-checking the recipient's email address for typos or unnecessary spaces. Learn more at http: //support.google.com/mail/bin/answer.py?answer=6596 do3si8775385pbc.262 - gsmtp ",
          "purchase":"PO1452297845",
          "event":"bounce",
          "email":"asdfasdflksjfe@sendgrid.com",
          "timestamp":1386637483,
          "smtp-id":"<142da08cd6e.5e4a.310b89@localhost.localdomain>",
          "type":"bounce",
          "category":["category1","category2","category3"],
          "id":"001"
        },
        {
          "email":"john.doe@gmail.com",
          "timestamp":1386638248,
          "uid":"123456",
          "purchase":"PO1452297845",
          "id":"001",
          "category":["category1","category2","category3"],
          "event":"unsubscribe"
        }
      ]"""
      val json = Json.parse(rawTestJson)
      val events: Seq[SendgridEvent] = Json.fromJson[Seq[SendgridEvent]](json).get
      events.size === 7
    }

    "parse test 2 json" in {
      val rawTestJson =
        """
          [{
            "sg_event_id":"H4MD4wzUTNCJ6H0hfjMLJQ",
            "sg_message_id":"1436e4644c0.5525.b1b754.filter-143.15334.52CC5BB77.0",
            "mail_id":"1f6cbc86-d98c-4a61-884f-39ba2f687f1e",
            "event":"processed",
            "email":"eng@42go.com",
            "timestamp":1389124537,
            "smtp-id":"<882573809.1.1389124535521.JavaMail.fortytwo@shoebox-demand-1>",
            "category":"healthcheck"
          }]
        """.stripMargin
      val json = Json.parse(rawTestJson)
      val events: Seq[SendgridEvent] = Json.fromJson[Seq[SendgridEvent]](json) match {
        case JsSuccess(e, _) => e
        case JsError(errors) => throw new Exception(errors mkString ",")
      }
      events.size === 1
      events.head.mailId.get === ExternalId[ElectronicMail]("1f6cbc86-d98c-4a61-884f-39ba2f687f1e")
    }

    "parse test 3 json" in {
      val rawTestJson =
        """
          [{
            "response":"421 4.7.0 [GL01] Message from (198.37.156.66) temporarily deferred - 4.16.50. Please refer to http://postmaster.yahoo.com/errors/postmaster-21.html ",
            "sg_event_id":"aTKuFOuORiGcvIS-IRybXg",
            "mail_id":"bfeed584-0b6a-45dc-a8fe-2d16212cd6e5",
            "event":"deferred",
            "email":"bcarty02@yahoo.com",
            "attempt":"1",
            "smtp-id":"<488076355.9.1389124255696.JavaMail.fortytwo@b02>",
            "timestamp":1389124625,
            "category":"message"
          }]
        """.stripMargin
      val json = Json.parse(rawTestJson)
      val events: Seq[SendgridEvent] = Json.fromJson[Seq[SendgridEvent]](json).get
      events.size === 1
      events.head.mailId.get === ExternalId[ElectronicMail]("bfeed584-0b6a-45dc-a8fe-2d16212cd6e5")
    }

    "parse test 4 json" in {
      val rawTestJson =
        """
          [
            {
              "sg_event_id":"VuKnr0RCR5i37GO-7JQAzw",
              "sg_message_id":"1436e4607de.538e.1998cb.filterdell-005.19934.52CC5BA82.0",
              "mail_id":"e3b9fc82-1e29-483a-b07c-0c29786b0653",
              "event":"processed",
              "email":"ray+test_to@42go.com",
              "timestamp":1389124521,
              "smtp-id":"<8762663.7.1389124519888.JavaMail.rng@rng-mbp>",
              "category":"email_confirmation"
            },
            {
              "email":"ray+test_to@42go.com",
              "timestamp":1389124522,
              "smtp-id":"<8762663.7.1389124519888.JavaMail.rng@rng-mbp>",
              "response":"250 2.0.0 OK 1389124522 gm1si32929800pac.71 - gsmtp ",
              "sg_event_id":"S32eNQKySce5hS1m6ekxdw",
              "category":"email_confirmation",
              "mail_id":"e3b9fc82-1e29-483a-b07c-0c29786b0653",
              "event":"delivered"
            }
          ]
        """.stripMargin
      val json = Json.parse(rawTestJson)
      val events: Seq[SendgridEvent] = Json.fromJson[Seq[SendgridEvent]](json).get
      events.size === 2
    }
  }
}

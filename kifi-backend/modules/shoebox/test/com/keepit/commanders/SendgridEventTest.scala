package com.keepit.commanders

import org.specs2.mutable.Specification
import play.api.libs.json.Json

class SendgridEventTest extends Specification {
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

  "SendgridEvent" should {

    "parse" in {
      val json = Json.parse(rawTestJson)
      val events: Seq[SendgridEvent] = Json.fromJson[Seq[SendgridEvent]](json).get
      events.size === 7
      events(0).sgMessageId.get === "142d9f3f351.7618.254f56.filter-147.22649.52A663508.0"
      events(0).category.size === 3
    }
  }
}

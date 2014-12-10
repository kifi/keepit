package com.keepit.common.mail.template

import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContextBuilder
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.libs.json.Json

class EmailTrackingParamTest extends Specification {

  "EmailLink conversions" should {
    def trackingParam = {
      val ctx = new HeimdalContextBuilder
      ctx += ("string", "Hi")
      ctx += ("number", 1)
      ctx += ("decimal", 2.2)
      ctx += ("boolT", true)
      ctx += ("boolF", false)
      ctx += ("list", Seq(1, 2, 3))
      ctx += ("datetime", new DateTime(2014, 8, 30, 13, 14, zones.UTC))

      EmailTrackingParam(
        subAction = Some("kifiLogo"),
        tip = Some(EmailTip.FriendRecommendations),
        variableComponents = Seq("wut"),
        auxiliaryData = Some(ctx.build))
    }

    "converts to and from json" in {
      val json = Json.toJson(trackingParam)
      val expectedJson = Json.obj(
        "a" -> Json.obj(
          "string" -> "Hi",
          "number" -> 1.0,
          "decimal" -> 2.2,
          "boolT" -> true,
          "boolF" -> false,
          "list" -> List(1, 2, 3),
          "datetime" -> "2014-08-30T13:14:00.000Z"),
        "l" -> "kifiLogo",
        "c" -> Seq("wut"),
        "t" -> Seq[String](),
        "t1" -> "PYMK"
      )

      val expectedTrackingParam = Json.fromJson[EmailTrackingParam](json).get
      json === expectedJson
      trackingParam === expectedTrackingParam
    }

    "converts to and from an encoded string" in {
      EmailTrackingParam.decode(EmailTrackingParam.encode(trackingParam)) === Right(trackingParam)
    }
  }
}

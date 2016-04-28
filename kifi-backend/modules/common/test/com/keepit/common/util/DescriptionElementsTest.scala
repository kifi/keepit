package com.keepit.common.util

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.BasicKeepEvent.BasicKeepEventId
import com.keepit.model._
import com.keepit.social.BasicAuthor
import org.joda.time.{ Duration, DateTime, Period }
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsObject, Json }

class DescriptionElementsTest extends Specification {
  "DescriptionElements" should {
    "format time periods properly" in {
      import DescriptionElements._
      val now = new DateTime
      val tests = Seq[(Period, String)](
        Period.millis(1) -> "just now",
        Period.millis(10) -> "just now",
        Period.seconds(1) -> "just now",
        Period.seconds(10) -> "just now",
        Period.minutes(1) -> "in the last minute",
        Period.minutes(10) -> "in the last 10 minutes",
        Period.hours(1) -> "in the last hour",
        Period.hours(10) -> "in the last 10 hours",
        Period.days(1) -> "in the last day",
        Period.days(5) -> "in the last 5 days",
        Period.days(10) -> "in the last week",
        Period.weeks(1) -> "in the last week",
        Period.weeks(2) -> "in the last 2 weeks",
        Period.weeks(10) -> "in the last 2 months",
        Period.months(1) -> "in the last month",
        Period.months(10) -> "in the last 10 months",
        Period.months(100) -> "in the last 8 years",
        Period.years(1) -> "in the last year",
        Period.years(10) -> "in the last decade"
      )

      for ((input, ans) <- tests) yield {
        DescriptionElements.formatPlain(inTheLast(input.toDurationTo(now))) === ans
      }
    }

    "format rich elements" in {
      import DescriptionElements._
      val author = BasicAuthor.KifiUser("1234567", "Hank Paulson", picture = "0.jpg", url = "/paulson")
      val library = BasicLibrary(PublicId[Library]("1234567"), "Soap Makers Inc.", "/paulson/soap", LibraryVisibility.DISCOVERABLE, color = None, slack = None)

      val header = DescriptionElements(author, "kept in", library)
      val body = DescriptionElements("Here is a note with [#hashtags] all over") // maybe todo(Cam): create a HashtagElement

      val event = BasicKeepEvent(
        id = BasicKeepEventId.InitialId(PublicId[Keep]("k4RmCCOy1adz")),
        author = author,
        KeepEventKind.Initial,
        header = header,
        body = body,
        timestamp = currentDateTime,
        source = None
      )
      val jsEvent = Json.toJson(event)
      (jsEvent \ "header").as[Seq[JsObject]].map { o => (o \ "text").as[String] } === Seq(author.name, " kept in ", library.name)
      (jsEvent \ "body").as[Seq[JsObject]].map { o => (o \ "text").as[String] } === Seq("Here is a note with [#hashtags] all over")
    }
  }
}

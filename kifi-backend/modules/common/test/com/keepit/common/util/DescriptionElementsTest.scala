package com.keepit.common.util

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.store.{ S3ImageConfig, ImagePath }
import com.keepit.common.time._
import com.keepit.model.BasicKeepEvent.BasicKeepEventId
import com.keepit.model._
import com.keepit.social.{ BasicUser, BasicAuthor }
import org.joda.time.{ DateTime, Period }
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsObject, Json }

class DescriptionElementsTest extends Specification {

  implicit val imageConfig = S3ImageConfig("", "http://dev.ezkeep.com:9000", isLocal = true)

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

    "internally format elements" in {
      import com.keepit.common.util.DescriptionElements._
      val user1 = BasicUser(ExternalId[User](), "Hank", "Paulson", "0.jpg", Username("hank"))
      val author = BasicAuthor.fromUser(user1)
      val library = BasicLibrary(PublicId[Library]("l1234567"), "Paper Street Soap Company", "/paulson/soap", LibraryVisibility.DISCOVERABLE, color = None, slack = None)
      val org = BasicOrganization(PublicId[Organization]("o7654321"), user1.externalId, OrganizationHandle("paperstreetsoap"), "Paper Street Soap Company", description = None, avatarPath = ImagePath("/"))

      def exampleForKind(kind: DescriptionElementKind): DescriptionElement = kind match {
        case DescriptionElementKind.Image => ImageElement(url = Some("https://www.flickr.com"), image = "/flickrImage")
        case DescriptionElementKind.ShowOriginal => ShowOriginalElement("first comes first", "second come second")
        case DescriptionElementKind.User => fromBasicUser(user1)
        case DescriptionElementKind.NonUser => NonUserElement("hankpaulson@paperstreet.club")
        case DescriptionElementKind.Author => fromBasicAuthor(author)
        case DescriptionElementKind.Library => fromBasicLibrary(library)
        case DescriptionElementKind.Organization => fromBasicOrg(org)
        case DescriptionElementKind.Text => TextElement("testing one two three", url = Some("https://www.testing.com"), hover = Some(DescriptionElements("woo look at me!")))
      }

      for (kind <- DescriptionElementKind.all) {
        val ex = exampleForKind(kind)
        val payload = DescriptionElement.format.writes(ex)
        DescriptionElement.format.reads(payload).asEither must beRight(ex)
      }
      1 === 1
    }
    "format invertably" in {
      import com.keepit.common.util.DescriptionElements._
      val orig: DescriptionElements = {
        val ryan = BasicUser(ExternalId[User](), "Ryan", "Brewster", "0.jpg", Username("ryanpbrewster"))
        val myMainLibrary = BasicLibrary(PublicId[Library]("l1234567"), "My Main Library", "/ryanpbrewster/main", LibraryVisibility.DISCOVERABLE, color = None, slack = None)
        DescriptionElements("Hello, I'm", ryan, "and I created", myMainLibrary)
      }
      val iter1 = Json.toJson(orig).as[DescriptionElements]
      val iter2 = Json.toJson(iter1).as[DescriptionElements]

      iter2 === iter1
    }
  }
}

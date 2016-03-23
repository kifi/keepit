package com.keepit.common.util

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.ExternalId
import com.keepit.common.json.TestHelper
import com.keepit.common.path.Path
import com.keepit.discussion.Message
import com.keepit.model.{ LibraryVisibility, BasicLibrary, Library, User }
import com.keepit.social.BasicAuthor
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsObject, Json }
import com.keepit.common.time._

class ActivityElementsTest extends Specification {
  "ActivityElements" should {
    "format properly" in {
      import ActivityElement._
      val author = BasicAuthor.KifiUser("1234567", "Hank Paulson", picture = "0.jpg", url = "/paulson")
      val library = BasicLibrary(PublicId[Library]("1234567"), "Soap Makers Inc.", "/paulson/soap", LibraryVisibility.DISCOVERABLE, color = None)

      val header = DescriptionElements(author, "kept in", library)
      val body = DescriptionElements("Here is a note with [#hashtags] all over") // maybe todo(Cam): create a HashtagElement

      val event = ActivityEvent(
        ActivityKind.Comment,
        "0,jpg",
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

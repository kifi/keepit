package com.keepit.search.feed

import org.joda.time.DateTime
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.social.BasicUser
import com.keepit.common.time._

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Feed(uri: NormalizedURI, sharingUsers: Seq[BasicUser], firstKeptAt: DateTime, totalKeepersSize: Int)

object Feed {
  implicit val format = (
    (__ \ 'uri).format[NormalizedURI] and
    (__ \ 'sharingUsers).format[Seq[BasicUser]] and
    (__ \ 'firstKeptAt).format(DateTimeJsonFormat) and
    (__ \ 'totalKeepersSize).format[Int]
  )(Feed.apply, unlift(Feed.unapply))
}

object FeedResult {
  def v1(feeds: Seq[Feed]): JsValue = {
    val jsVals = feeds.map { feed =>
      val title = feed.uri.title.getOrElse("No Title")
      Json.obj(
        "title" -> title,
        "url" -> feed.uri.url,
        "sharingUsers" -> feed.sharingUsers,
        "firstKeptAt" -> feed.firstKeptAt,
        "totalKeeperSize" -> feed.totalKeepersSize
      )
    }
    JsArray(jsVals)
  }
}

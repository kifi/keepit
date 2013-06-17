package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model._
import play.api.libs.json._
import com.keepit.common.db.{Id, State}
import com.keepit.common.db.SequenceNumber
import play.api.libs.functional.syntax._
import org.joda.time.DateTime
class BookmarkSerializer extends Writes[Bookmark] {

  def writes(bookmark: Bookmark): JsValue =
    JsObject(Seq(
      "id" -> JsString(bookmark.externalId.toString),
      "title" -> bookmark.title.map(JsString).getOrElse(JsNull),
      "url" -> JsString(bookmark.url),
      "isPrivate" -> JsBoolean(bookmark.isPrivate),
      "state" -> JsString(bookmark.state.toString)))
}

class FullBookmarkSerializer extends Format[Bookmark] {
  implicit val sourceFormat = Json.format[BookmarkSource]
  implicit val bookmarkFormat = (
  (__ \'id).formatNullable(Id.format[Bookmark]) and
  (__ \'createdAt).format[DateTime] and
  (__ \'updatedAt).format[DateTime] and
  (__ \'externalId).format(ExternalId.format[Bookmark]) and
  (__ \'title).formatNullable[String] and
  (__ \'uriId).format(Id.format[NormalizedURI]) and
  (__ \'urlId).formatNullable(Id.format[URL]) and
  (__ \'url).format[String] and
  (__ \'bookmarkPath).formatNullable[String] and
  (__ \'isPrivate).format[Boolean] and
  (__ \'userId).format(Id.format[User]) and
  (__ \'state).format(State.format[Bookmark]) and
  (__ \'source).format[BookmarkSource] and
  (__ \'kifiInstallation).formatNullable(ExternalId.format[KifiInstallation]) and
  (__ \'seq).format[Long].inmap(SequenceNumber.apply, unlift(SequenceNumber.unapply))
  )(Bookmark.apply, unlift(Bookmark.unapply))

  def writes(bookmark: Bookmark): JsValue = Json.toJson(bookmark)
  def reads(json: JsValue): JsResult[Bookmark] = Json.fromJson[Bookmark](json)
}

object BookmarkSerializer {
  implicit val bookmarkSerializer = new BookmarkSerializer
  implicit val fullBookmarkSerializer = new FullBookmarkSerializer
}

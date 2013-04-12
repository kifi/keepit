package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model.Bookmark

import play.api.libs.json._

class BookmarkSerializer extends Writes[Bookmark] {

  def writes(bookmark: Bookmark): JsValue =
    JsObject(Seq(
      "id" -> JsString(bookmark.externalId.toString),
      "externalId" -> JsString(bookmark.externalId.toString),  // TODO: deprecate, eliminate
      "title" -> bookmark.title.map(JsString).getOrElse(JsNull),
      "url" -> JsString(bookmark.url),
      "isPrivate" -> JsBoolean(bookmark.isPrivate),
      "state" -> JsString(bookmark.state.toString)))

  def writes(bookmarks: List[Bookmark]): JsValue =
    JsArray(bookmarks map { bookmark =>
      JsObject(Seq(
        "bookmarkId" -> JsNumber(bookmark.id.get.id),
        "bookmarkObject" -> writes(bookmark)))
    })

}

object BookmarkSerializer {
  implicit val bookmarkSerializer = new BookmarkSerializer
}

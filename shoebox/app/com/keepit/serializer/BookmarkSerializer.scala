package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model.Bookmark

import play.api.libs.json._

class BookmarkSerializer extends Writes[Bookmark] {

  def writes(bookmark: Bookmark): JsValue =
    JsObject(Seq(
      "id" -> JsString(bookmark.externalId.toString),
      "title" -> bookmark.title.map(JsString).getOrElse(JsNull),
      "url" -> JsString(bookmark.url),
      "isPrivate" -> JsBoolean(bookmark.isPrivate),
      "state" -> JsString(bookmark.state.toString)))
}

object BookmarkSerializer {
  implicit val bookmarkSerializer = new BookmarkSerializer
}

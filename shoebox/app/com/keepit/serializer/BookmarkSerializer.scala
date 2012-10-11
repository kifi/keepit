package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model.Bookmark

import play.api.libs.json._

class BookmarkSerializer extends Writes[Bookmark] {
  
  def writes(bookmark: Bookmark): JsValue =
    JsObject(List(
      "externalId"  -> JsString(bookmark.externalId.toString),
      "title"  -> JsString(bookmark.title),
      "url"  -> JsString(bookmark.url),
      "isPrivate"  -> JsBoolean(bookmark.isPrivate)
    ))

  def writes (bookmarks: List[Bookmark]): JsValue = 
    JsArray(bookmarks map { bookmark => 
      JsObject(List(
        "bookmarkId" -> JsNumber(bookmark.id.get.id),
        "bookmarkObject" -> BookmarkSerializer.bookmarkSerializer.writes(bookmark)
      ))
    })
    
}

object BookmarkSerializer {
  implicit val bookmarkSerializer = new BookmarkSerializer
}

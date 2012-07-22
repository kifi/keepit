package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model.Bookmark

import play.api.libs.json._

class BookmarkSerializer extends Reads[Bookmark] with Writes[Bookmark] {
  
  def writes(bookmark: Bookmark): JsValue =
    JsObject(List(
      "exuuid"  -> JsString(bookmark.externalId.toString),
      "title"  -> JsString(bookmark.title),
      "url"  -> JsString(bookmark.url),
      "normalizedUrl"  -> JsString(bookmark.normalizedUrl),
      "urlHash"  -> JsString(bookmark.urlHash),
      "isPrivate"  -> JsBoolean(bookmark.isPrivate)
    ))

  def writes (bookmarks: List[Bookmark]): JsValue = 
    JsArray(bookmarks map { bookmark => 
      JsObject(List(
        "bookmarkId" -> JsNumber(bookmark.id.get.id),
        "bookmarkObject" -> BookmarkSerializer.bookmarkSerializer.writes(bookmark)
      ))
    })

  def reads(json: JsValue) = Bookmark(
    externalId = (json \ "exuuid").asOpt[String].map(ExternalId[Bookmark](_)).getOrElse(ExternalId()),
    title = (json \ "title").as[String],
    url = (json \ "url").as[String],
    normalizedUrl = (json \ "normalizedUrl").as[String],
    urlHash = (json \ "urlHash").as[String],
    isPrivate = (json \ "isPrivate").as[Boolean]
  )
    
}

object BookmarkSerializer {
  implicit val bookmarkSerializer = new BookmarkSerializer
}

package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.controllers._

import play.api.libs.json._

class BookmarkPersonalSearchResultSerializer extends Writes[BookmarkPersonalSearchResult] {
  //case class BookmarkPersonalSearchResult(bookmark: Bookmark, count: Int, users: Seq[User], score: Float)
  def writes(res: BookmarkPersonalSearchResult): JsValue =
    JsObject(List(
      "count"  -> JsString(res.count.toString()),
      "bookmark" -> BookmarkSerializer.bookmarkSerializer.writes(res.bookmark),
      "score" -> JsNumber(res.score),
      "users" -> UserSerializer.userSerializer.writes(res.users)
    ))

  def writes (ress: Seq[BookmarkPersonalSearchResult]): JsValue = 
    JsArray(ress map { res => 
      BookmarkPersonalSearchResultSerializer.resSerializer.writes(res)
    })

}

object BookmarkPersonalSearchResultSerializer {
  implicit val resSerializer = new BookmarkPersonalSearchResultSerializer
}

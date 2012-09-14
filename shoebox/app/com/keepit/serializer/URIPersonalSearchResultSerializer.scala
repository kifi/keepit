package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.controllers._

import play.api.libs.json._

class URIPersonalSearchResultSerializer extends Writes[PersonalSearchResult] {
  //case class BookmarkPersonalSearchResult(bookmark: Bookmark, count: Int, users: Seq[User], score: Float)
  def writes(res: PersonalSearchResult): JsValue =
    JsObject(List(
      "count"  -> JsString(res.count.toString()),
      "bookmark" -> BookmarkSerializer.bookmarkSerializer.writes(res.uris),
      "score" -> JsNumber(res.score),
      "users" -> UserSerializer.userSerializer.writes(res.users)
    ))

  def writes (ress: Seq[PersonalSearchResult]): JsValue = 
    JsArray(ress map { res => 
      BookmarkPersonalSearchResultSerializer.resSerializer.writes(res)
    })
}

object URIPersonalSearchResultSerializer {
  implicit val resSerializer = new URIPersonalSearchResultSerializer
}

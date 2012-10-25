package com.keepit.serializer

import com.keepit.common.logging.Logging
import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.controllers._
import play.api.libs.json._

class URIPersonalSearchResultSerializer extends Writes[PersonalSearchResult] with Logging {
  //case class BookmarkPersonalSearchResult(bookmark: Bookmark, count: Int, users: Seq[User], score: Float)
  def writes(res: PersonalSearchResult): JsValue =
    try {
      JsObject(List(
        "count"  -> JsString(res.count.toString()),
        "bookmark" -> NormalizedURISerializer.normalizedURISerializer.writes(res.uri),
        "users" -> UserWithSocialSerializer.userWithSocialSerializer.writes(res.users),
        "score" -> JsNumber(res.score),
        "isMyBookmark" -> JsBoolean(res.isMyBookmark),
        "isPrivate" -> JsBoolean(res.isPrivate)
      ))
    } catch {
      case e =>
        log.error("can't serialize %s".format(res))
        throw e
    }

  def writes (ress: Seq[PersonalSearchResult]): JsValue = 
    JsArray(ress map { res => 
      URIPersonalSearchResultSerializer.resSerializer.writes(res)
    })
}

object URIPersonalSearchResultSerializer {
  implicit val resSerializer = new URIPersonalSearchResultSerializer
}

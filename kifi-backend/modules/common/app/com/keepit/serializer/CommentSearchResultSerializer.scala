package com.keepit.serializer

import com.keepit.common.logging.Logging
import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.search.comment.CommentHit
import com.keepit.search.comment.CommentSearchResult
import play.api.libs.json._

class CommentSearchResultSerializer extends Writes[CommentSearchResult] with Logging {
  def writes(res: CommentSearchResult): JsValue = {
    try {
      Json.obj(
        "hits" -> JsArray((res.hits map writes).toSeq),
        "context" -> JsString(res.context)
      )
    } catch {
      case e: Throwable =>
        log.error("can't serialize %s".format(res))
        throw e
    }
  }

  private def writes(hit: CommentHit): JsValue = {
    // more attributes?
    Json.obj(
      "id" -> hit.externalId.toString
    )
  }
}

object CommentSearchResultSerializer {
  implicit val resSerializer = new CommentSearchResultSerializer
}


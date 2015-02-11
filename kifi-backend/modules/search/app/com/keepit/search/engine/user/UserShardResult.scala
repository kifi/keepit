package com.keepit.search.engine.user

import com.keepit.common.db.Id
import com.keepit.common.json.TupleFormat
import com.keepit.model.{ User, Keep, Library }
import com.keepit.search.index.graph.keep.KeepRecord
import play.api.libs.json.Json

case class UserShardHit(
  id: Id[User],
  score: Float,
  visibility: Int,
  library: Option[Id[Library]])

object UserShardHit {
  implicit val format = Json.format[UserShardHit]
}

case class UserShardResult(hits: Seq[UserShardHit], explanation: Option[UserSearchExplanation])

object UserShardResult {
  implicit val format = Json.format[UserShardResult]
}

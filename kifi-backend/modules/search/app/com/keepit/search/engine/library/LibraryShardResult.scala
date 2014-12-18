package com.keepit.search.engine.library

import com.keepit.common.db.Id
import com.keepit.common.json.TupleFormat
import com.keepit.model.{ Keep, Library }
import com.keepit.search.index.graph.keep.KeepRecord
import play.api.libs.json.Json

case class LibraryShardHit(
  id: Id[Library],
  score: Float,
  visibility: Int,
  keep: Option[(Id[Keep], KeepRecord)])

object LibraryShardHit {
  implicit val format = {
    implicit val tupleFormat = TupleFormat.tuple2Format[Id[Keep], KeepRecord]
    Json.format[LibraryShardHit]
  }
}

case class LibraryShardResult(hits: Seq[LibraryShardHit], show: Boolean, explanation: Option[LibrarySearchExplanation])

object LibraryShardResult {
  implicit val format = Json.format[LibraryShardResult]
}

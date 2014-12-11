package com.keepit.search.engine.result

import com.keepit.model.{ Keep, Library }
import com.keepit.common.db.{ Id }
import play.api.libs.json.Json
import com.keepit.search.index.graph.keep.KeepRecord
import com.keepit.common.json.TupleFormat

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

case class LibraryShardResult(hits: Seq[LibraryShardHit], show: Boolean)

object LibraryShardResult {
  implicit val format = Json.format[LibraryShardResult]
}

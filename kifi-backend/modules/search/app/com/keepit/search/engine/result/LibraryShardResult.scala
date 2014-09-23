package com.keepit.search.engine.result

import com.keepit.model.{ Keep, Library }
import com.keepit.common.db.{ Id }
import play.api.libs.json.Json

case class LibraryShardResult(hits: Seq[LibraryShardHit])

case class LibraryShardHit(
  id: Id[Library],
  score: Float,
  visibility: Int,
  keepId: Option[Id[Keep]])

object LibraryShardHit {
  implicit val format = Json.format[LibraryShardHit]
}

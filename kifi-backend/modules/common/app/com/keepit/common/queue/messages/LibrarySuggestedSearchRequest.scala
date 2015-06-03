package com.keepit.common.queue.messages

import com.keepit.common.db.Id
import com.keepit.model.Library
import play.api.libs.json._

case class LibrarySuggestedSearchRequest(id: Id[Library])

object LibrarySuggestedSearchRequest {
  implicit val format = Format(
    __.read[Id[Library]].map(LibrarySuggestedSearchRequest.apply),
    new Writes[LibrarySuggestedSearchRequest] { def writes(o: LibrarySuggestedSearchRequest) = Json.toJson(o.id) }
  )
}

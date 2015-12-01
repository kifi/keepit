package com.keepit.eliza.model

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.ExternalId
import com.keepit.model.{ Library, User }
import play.api.libs.json.{ Json, Reads }

case class ExternalDiscussionCreateRequest(
  users: Set[ExternalId[User]],
  libraries: Set[PublicId[Library]],
  url: String,
  canonical: Option[String],
  source: Option[MessageSource],
  initialMessage: String)

object ExternalDiscussionCreateRequest {
  implicit val reads: Reads[ExternalDiscussionCreateRequest] = Json.reads[ExternalDiscussionCreateRequest]
}


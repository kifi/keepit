package com.keepit.model.view

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.{ SequenceNumber, State, Id }
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.keepit.model.{ LibraryAccess, LibraryMembership, User, Library }
import org.joda.time.DateTime
import play.api.libs.json.Json

import scala.concurrent.duration.Duration

case class LibraryMembershipView(
  id: Id[LibraryMembership],
  libraryId: Id[Library],
  userId: Id[User],
  access: LibraryAccess,
  createdAt: DateTime = currentDateTime,
  state: State[LibraryMembership],
  seq: SequenceNumber[LibraryMembership],
  showInSearch: Boolean)

object LibraryMembershipView {
  implicit val format = Json.format[LibraryMembershipView]
}


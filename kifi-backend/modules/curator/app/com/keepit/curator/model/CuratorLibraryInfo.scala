package com.keepit.curator.model

import com.keepit.common.db.{ States, State, Id }
import com.keepit.common.time.currentDateTime
import com.keepit.model.{ LibraryMembership, LibraryKind, Library, User }
import org.joda.time.DateTime
import com.keepit.common.time._

case class CuratorLibraryInfo(
    id: Option[Id[CuratorLibraryInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updateAt: DateTime = currentDateTime,
    userId: Id[User],
    libraryId: Id[Library],
    kind: LibraryKind,
    state: State[CuratorLibraryInfo],
    membershipState: State[LibraryMembership]) {
  def withId(id: Id[CuratorLibraryInfo]): CuratorLibraryInfo = this.copy(id = Some(id))
}

object CuratorLibraryInfoStates extends States[CuratorLibraryInfo]

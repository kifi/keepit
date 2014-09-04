package com.keepit.curator.model

import com.keepit.common.db.{ ModelWithState, Model, States, State, Id }
import com.keepit.common.time.currentDateTime
import com.keepit.model.{ LibraryAccess, LibraryKind, Library, User }
import org.joda.time.DateTime
import com.keepit.common.time._

case class CuratorLibraryMembershipInfo(
    id: Option[Id[CuratorLibraryMembershipInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updateAt: DateTime = currentDateTime,
    userId: Id[User],
    libraryId: Id[Library],
    access: LibraryAccess,
    kind: LibraryKind,
    state: State[CuratorLibraryMembershipInfo]) extends Model[CuratorLibraryMembershipInfo] with ModelWithState[CuratorLibraryMembershipInfo] {
  def withId(id: Id[CuratorLibraryMembershipInfo]): CuratorLibraryMembershipInfo = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): CuratorLibraryMembershipInfo = this.copy(updateAt = updateTime)
}

object CuratorLibraryMembershipInfoStates extends States[CuratorLibraryMembershipInfo]

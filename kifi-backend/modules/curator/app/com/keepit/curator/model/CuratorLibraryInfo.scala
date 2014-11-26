package com.keepit.curator.model

import com.keepit.common.db.{ SequenceNumber, ModelWithSeqNumber, Id, Model, ModelWithState, State, States }
import com.keepit.common.time._
import com.keepit.model.{ LibraryKind, LibraryVisibility, Library, User }
import org.joda.time.DateTime

case class CuratorLibraryInfo(
  id: Option[Id[CuratorLibraryInfo]] = None,
  createdAt: DateTime = currentDateTime,
  updateAt: DateTime = currentDateTime,
  seq: SequenceNumber[CuratorLibraryInfo] = SequenceNumber.ZERO,
  libraryId: Id[Library],
  ownerId: Id[User],
  memberCount: Int,
  keepCount: Int,
  visibility: LibraryVisibility,
  lastKept: Option[DateTime] = None,
  lastFollowed: Option[DateTime] = None,
  kind: LibraryKind,
  libraryLastUpdated: DateTime,
  state: State[CuratorLibraryInfo])
    extends Model[CuratorLibraryInfo] with ModelWithState[CuratorLibraryInfo] with ModelWithSeqNumber[CuratorLibraryInfo] {

  def withId(id: Id[CuratorLibraryInfo]): CuratorLibraryInfo = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): CuratorLibraryInfo = this.copy(updateAt = updateTime)
}

object CuratorLibraryInfoStates extends States[CuratorLibraryInfo]

package com.keepit.curator.model

import com.keepit.common.db.{ States, State, Id }
import com.keepit.common.time.currentDateTime
import com.keepit.model.{ LibraryKind, Library, User, NormalizedURI }
import org.joda.time.DateTime

case class CuratorLibraryInfo(
  id: Option[Id[CuratorLibraryInfo]] = None,
  createdAt: DateTime = currentDateTime,
  updateAt: DateTime = currentDateTime,
  uriId: Option[Id[NormalizedURI]], //the library membership change may target to the uri we ingested for keeps
  userSet: Set[Id[User]],
  libraryId: Id[Library],
  kind: LibraryKind,
  state: State[CuratorLibraryInfo]) {
  def withId(id: Id[CuratorLibraryInfo]): CuratorLibraryInfo = this.copy(id = Some(id))
}

object CuratorLibraryInfoStates extends States[CuratorLibraryInfo]

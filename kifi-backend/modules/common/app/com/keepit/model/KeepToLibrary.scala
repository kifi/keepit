package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime

case class KeepToLibrary(
  id: Option[Id[KeepToLibrary]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[KeepToLibrary] = KeepToLibraryStates.ACTIVE,
  keepId: Id[Keep],
  libraryId: Id[Library],
  addedAt: DateTime = currentDateTime,
  addedBy: Id[User],
  // A bunch of denormalized fields from Keep
  uriId: Id[NormalizedURI],
  // and from Library
  visibility: LibraryVisibility,
  organizationId: Option[Id[Organization]],
  lastActivityAt: DateTime) // denormalized from keep
    extends ModelWithState[KeepToLibrary] {

  def withId(id: Id[KeepToLibrary]): KeepToLibrary = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): KeepToLibrary = this.copy(updatedAt = now)
  def withState(newState: State[KeepToLibrary]): KeepToLibrary = this.copy(state = newState)
  def withVisibility(newVisibility: LibraryVisibility): KeepToLibrary = this.copy(visibility = newVisibility)
  def withOrganizationId(newOrgIdOpt: Option[Id[Organization]]): KeepToLibrary = this.copy(organizationId = newOrgIdOpt)
  def withAddedAt(time: DateTime): KeepToLibrary = this.copy(addedAt = time)
  def withAddedBy(newOwnerId: Id[User]): KeepToLibrary = this.copy(addedBy = newOwnerId)
  def withUriId(newUriId: Id[NormalizedURI]) = this.copy(uriId = newUriId)

  // denormalized from Keep.lastActivityAt, use in KeepCommander.updateLastActivityAtIfLater
  def withLastActivityAt(time: DateTime): KeepToLibrary = this.copy(lastActivityAt = time)

  def isActive = state == KeepToLibraryStates.ACTIVE
  def isInactive = state == KeepToLibraryStates.INACTIVE

  def sanitizeForDelete = this.withState(KeepToLibraryStates.INACTIVE)
}

object KeepToLibraryStates extends States[KeepToLibrary]


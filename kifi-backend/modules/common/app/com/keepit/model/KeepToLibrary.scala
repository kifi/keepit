package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.json.Json
import play.api.mvc.Results.Status
import play.api.http.Status._

import scala.util.control.NoStackTrace

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
  isPrimary: Boolean = true,
  // and from Library
  visibility: LibraryVisibility,
  organizationId: Option[Id[Organization]])
    extends ModelWithState[KeepToLibrary] {

  def withId(id: Id[KeepToLibrary]): KeepToLibrary = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): KeepToLibrary = this.copy(updatedAt = now)
  def withState(newState: State[KeepToLibrary]): KeepToLibrary = this.copy(state = newState)
  def withVisibility(newVisibility: LibraryVisibility): KeepToLibrary = this.copy(visibility = newVisibility)
  def withOrganizationId(newOrgIdOpt: Option[Id[Organization]]): KeepToLibrary = this.copy(organizationId = newOrgIdOpt)
  def withAddedBy(newOwnerId: Id[User]): KeepToLibrary = this.copy(addedBy = newOwnerId)
  def withUriId(newUriId: Id[NormalizedURI]) = this.copy(uriId = newUriId)
  def withPrimary(isPrimary: Boolean): KeepToLibrary = this.copy(isPrimary = isPrimary)
  def nonPrimary: KeepToLibrary = this.withPrimary(false)

  def isActive = state == KeepToLibraryStates.ACTIVE
  def isInactive = state == KeepToLibraryStates.INACTIVE

  def sanitizeForDelete = this.withState(KeepToLibraryStates.INACTIVE).nonPrimary
}

object KeepToLibrary {
  // is_primary: trueOrNull in db
  def applyFromDbRow(id: Option[Id[KeepToLibrary]], createdAt: DateTime, updatedAt: DateTime, state: State[KeepToLibrary],
    keepId: Id[Keep], libraryId: Id[Library], addedAt: DateTime, addedBy: Id[User],
    uriId: Id[NormalizedURI], isPrimary: Option[Boolean],
    libraryVisibility: LibraryVisibility, libraryOrganizationId: Option[Id[Organization]]): KeepToLibrary = {
    KeepToLibrary(
      id, createdAt, updatedAt, state,
      keepId, libraryId, addedAt, addedBy,
      uriId, isPrimary.getOrElse(false),
      libraryVisibility, libraryOrganizationId)
  }

  def trueOrNull(b: Boolean): Option[Boolean] = if (b) Some(true) else None
  def unapplyToDbRow(ktl: KeepToLibrary) = {
    Some(
      (ktl.id, ktl.createdAt, ktl.updatedAt, ktl.state,
        ktl.keepId, ktl.libraryId, ktl.addedAt, ktl.addedBy,
        ktl.uriId, trueOrNull(ktl.isPrimary),
        ktl.visibility, ktl.organizationId)
    )
  }
}

object KeepToLibraryStates extends States[KeepToLibrary]

sealed abstract class KeepToLibraryFail(val status: Int, val message: String) extends Exception(message) with NoStackTrace {
  def asErrorResponse = Status(status)(Json.obj("error" -> message))
}
object KeepToLibraryFail {
  case object NOT_IN_LIBRARY extends KeepToLibraryFail(BAD_REQUEST, "keep_not_in_library")

  def apply(str: String): KeepToLibraryFail = {
    str match {
      case NOT_IN_LIBRARY.message => NOT_IN_LIBRARY
    }
  }
}

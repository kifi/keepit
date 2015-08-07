package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.json.Json
import play.api.mvc.Results.Status
import play.api.http.Status._

case class KeepToLibrary(
  id: Option[Id[KeepToLibrary]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[KeepToLibrary] = KeepToLibraryStates.ACTIVE,
  keepId: Id[Keep],
  libraryId: Id[Library],
  addedAt: Option[DateTime] = Some(currentDateTime),
  addedBy: Id[User])
    extends ModelWithState[KeepToLibrary] {

  def withId(id: Id[KeepToLibrary]): KeepToLibrary = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): KeepToLibrary = this.copy(updatedAt = now)
  def withState(newState: State[KeepToLibrary]): KeepToLibrary = this.copy(state = newState)

  def isActive = state == KeepToLibraryStates.ACTIVE
  def isInactive = state == KeepToLibraryStates.INACTIVE
}

object KeepToLibraryStates extends States[KeepToLibrary]

sealed abstract class KeepToLibraryFail(val status: Int, val message: String) {
  def asErrorResponse = Status(status)(Json.obj("error" -> message))
}
object KeepToLibraryFail {
  case object INSUFFICIENT_PERMISSIONS extends KeepToLibraryFail(FORBIDDEN, "insufficient_permissions")
  case object NOT_IN_LIBRARY extends KeepToLibraryFail(BAD_REQUEST, "keep_not_in_library")

  def apply(str: String): KeepToLibraryFail = {
    str match {
      case INSUFFICIENT_PERMISSIONS.message => INSUFFICIENT_PERMISSIONS
      case NOT_IN_LIBRARY.message => NOT_IN_LIBRARY
    }
  }
}

sealed abstract class KeepToLibraryRequest {
  def keepId: Id[Keep]
  def libraryId: Id[Library]
  def requesterId: Id[User]
}

case class KeepToLibraryInternRequest(
  keepId: Id[Keep],
  libraryId: Id[Library],
  requesterId: Id[User]) extends KeepToLibraryRequest
case class KeepToLibraryInternResponse(ktl: KeepToLibrary)

case class KeepToLibraryRemoveRequest(
  keepId: Id[Keep],
  libraryId: Id[Library],
  requesterId: Id[User]) extends KeepToLibraryRequest
case class KeepToLibraryRemoveResponse(dummy: Boolean = false)

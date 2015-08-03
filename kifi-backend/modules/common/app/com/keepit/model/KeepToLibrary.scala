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
  keeperId: Id[User])
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
  case object ALREADY_LINKED extends KeepToLibraryFail(BAD_REQUEST, "link_already_exists")
  case object NOT_LINKED extends KeepToLibraryFail(BAD_REQUEST, "link_does_not_exist")

  def apply(str: String): KeepToLibraryFail = {
    str match {
      case INSUFFICIENT_PERMISSIONS.message => INSUFFICIENT_PERMISSIONS
      case ALREADY_LINKED.message => ALREADY_LINKED
      case NOT_LINKED.message => NOT_LINKED
    }
  }
}

sealed abstract class KeepToLibraryRequest {
  def keepId: Id[Keep]
  def libraryId: Id[Library]
  def requesterId: Id[User]
}

case class KeepToLibraryAttachRequest(
  keepId: Id[Keep],
  libraryId: Id[Library],
  requesterId: Id[User]) extends KeepToLibraryRequest
case class KeepToLibraryAttachResponse(link: KeepToLibrary)

case class KeepToLibraryDetachRequest(
  keepId: Id[Keep],
  libraryId: Id[Library],
  requesterId: Id[User]) extends KeepToLibraryRequest
case class KeepToLibraryDetachResponse(dummy: Boolean = false)

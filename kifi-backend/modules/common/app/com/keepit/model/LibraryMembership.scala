package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model.view.LibraryMembershipView
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.json._

case class LibraryMembership(
    id: Option[Id[LibraryMembership]] = None,
    libraryId: Id[Library],
    userId: Id[User],
    access: LibraryAccess,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[LibraryMembership] = LibraryMembershipStates.ACTIVE,
    seq: SequenceNumber[LibraryMembership] = SequenceNumber.ZERO,
    showInSearch: Boolean,
    visibility: LibraryMembershipVisibility, //using this field only if the user is the LibraryAccess is OWNER (may change in the future)
    lastViewed: Option[DateTime] = None,
    lastEmailSent: Option[DateTime] = None) extends ModelWithState[LibraryMembership] with ModelWithSeqNumber[LibraryMembership] {

  def withId(id: Id[LibraryMembership]): LibraryMembership = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): LibraryMembership = this.copy(updatedAt = now)
  def withState(newState: State[LibraryMembership]): LibraryMembership = this.copy(state = newState)

  override def toString: String = s"LibraryMembership[id=$id,libraryId=$libraryId,userId=$userId,access=$access,state=$state]"

  def canInsert: Boolean = access == LibraryAccess.OWNER || access == LibraryAccess.READ_WRITE || access == LibraryAccess.READ_INSERT
  def canWrite: Boolean = access == LibraryAccess.OWNER || access == LibraryAccess.READ_WRITE
  def isOwner: Boolean = access == LibraryAccess.OWNER

  def toLibraryMembershipView: LibraryMembershipView =
    LibraryMembershipView(id = id.get, libraryId = libraryId, userId = userId, access = access, createdAt = createdAt, state = state, seq = seq, showInSearch = showInSearch)
}

case class LibraryMembershipVisibility(value: String)

object LibraryMembershipVisibility {
  implicit val format = Json.format[LibraryMembershipVisibility]
}

object LibraryMembershipVisibilityStates {
  val HIDDEN = LibraryMembershipVisibility("hidden")
  val VISIBLE = LibraryMembershipVisibility("visible")
}

object LibraryMembershipStates extends States[LibraryMembership]

sealed abstract class LibraryAccess(val value: String, val priority: Int)

object LibraryAccess {
  case object READ_ONLY extends LibraryAccess("read_only", 0)
  case object READ_INSERT extends LibraryAccess("read_insert", 1)
  case object READ_WRITE extends LibraryAccess("read_write", 2)
  case object OWNER extends LibraryAccess("owner", 3)

  implicit def format[T]: Format[LibraryAccess] =
    Format(__.read[String].map(LibraryAccess(_)), new Writes[LibraryAccess] { def writes(o: LibraryAccess) = JsString(o.value) })

  implicit def ord: Ordering[LibraryAccess] = new Ordering[LibraryAccess] {
    def compare(x: LibraryAccess, y: LibraryAccess): Int = x.priority compare y.priority
  }

  def apply(str: String) = {
    str match {
      case READ_ONLY.value => READ_ONLY
      case READ_INSERT.value => READ_INSERT
      case READ_WRITE.value => READ_WRITE
      case OWNER.value => OWNER
    }
  }

  def getAll() = Seq(OWNER, READ_WRITE, READ_INSERT, READ_ONLY)
}

package com.keepit.model

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

case class LibraryMembership(
    id: Option[Id[LibraryMembership]] = None,
    libraryId: Id[Library],
    userId: Id[User],
    access: LibraryAccess,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[LibraryMembership] = LibraryMembershipStates.ACTIVE,
    seq: SequenceNumber[LibraryMembership] = SequenceNumber.ZERO,
    showInSearch: Boolean) extends ModelWithState[LibraryMembership] with ModelWithSeqNumber[LibraryMembership] {

  def withId(id: Id[LibraryMembership]): LibraryMembership = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): LibraryMembership = this.copy(updatedAt = now)
  def withState(newState: State[LibraryMembership]): LibraryMembership = this.copy(state = newState)

  override def toString: String = s"LibraryMembership[id=$id,libraryId=$libraryId,userId=$userId,access=$access,state=$state]"
}

object LibraryMembership {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[LibraryMembership]) and
    (__ \ 'libraryId).format[Id[Library]] and
    (__ \ 'userId).format[Id[User]] and
    (__ \ 'access).format[LibraryAccess] and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[LibraryMembership]) and
    (__ \ 'seq).format(SequenceNumber.format[LibraryMembership]) and
    (__ \ 'showInSearch).format[Boolean]
  )(LibraryMembership.apply, unlift(LibraryMembership.unapply))
}

case class LibraryMembershipIdKey(id: Id[LibraryMembership]) extends Key[LibraryMembership] {
  override val version = 0
  val namespace = "library_membership_by_id"
  def toKey(): String = id.id.toString
}
class LibraryMembershipIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibraryMembershipIdKey, LibraryMembership](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object LibraryMembershipStates extends States[LibraryMembership]

sealed abstract class LibraryAccess(val value: String)

object LibraryAccess {
  case object READ_ONLY extends LibraryAccess("read_only")
  case object READ_INSERT extends LibraryAccess("read_insert")
  case object READ_WRITE extends LibraryAccess("read_write")
  case object OWNER extends LibraryAccess("owner")

  implicit def format[T]: Format[LibraryAccess] =
    Format(__.read[String].map(LibraryAccess(_)), new Writes[LibraryAccess] { def writes(o: LibraryAccess) = JsString(o.value) })

  def apply(str: String) = {
    str match {
      case READ_ONLY.value => READ_ONLY
      case READ_INSERT.value => READ_INSERT
      case READ_WRITE.value => READ_WRITE
      case OWNER.value => OWNER
    }
  }
}

package com.keepit.model

import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key}
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

case class LibraryMember(
                     id: Option[Id[LibraryMember]] = None,
                     libraryId: Id[Library],
                     userId: Id[User],
                     permission: LibraryMemberPrivacy = LibraryMemberPrivacy.READ_ONLY,
                     createdAt: DateTime = currentDateTime,
                     updatedAt: DateTime = currentDateTime,
                     state: State[LibraryMember] = LibraryMemberStates.ACTIVE,
                     seq: SequenceNumber[LibraryMember] = SequenceNumber.ZERO
                     ) extends ModelWithState[LibraryMember] with ModelWithSeqNumber[LibraryMember] {
  def isActive: Boolean = state == LibraryMemberStates.ACTIVE
  def withId(id: Id[LibraryMember]): LibraryMember = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): LibraryMember = this.copy(updatedAt = now)
  def withState(newState: State[LibraryMember]): LibraryMember = this.copy(state = newState)
  def withPermission(newPerm: LibraryMemberPrivacy) = this.copy(permission = newPerm)

  override def toString: String = s"LibraryMember[id=$id,libraryId=$libraryId,userId=$userId,permission=$permission,state=$state]"
}

object LibraryMember {
  implicit def format = (
  (__ \ 'id).formatNullable(Id.format[LibraryMember]) and
    (__ \ 'libraryId).format[Id[Library]] and
    (__ \ 'userId).format[Id[User]] and
    (__ \ 'permission).format[LibraryMemberPrivacy] and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[LibraryMember]) and
    (__ \ 'seq).format(SequenceNumber.format[LibraryMember])
    )(LibraryMember.apply, unlift(LibraryMember.unapply))
}

case class LibraryMemberIdKey(id: Id[LibraryMember]) extends Key[LibraryMember] {
  override val version = 0
  val namespace = "library__member_by_id"
  def toKey(): String = id.id.toString
}
class LibraryMemberIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibraryMemberIdKey, LibraryMember](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

object LibraryMemberStates extends States[LibraryMember]

case class LibraryMemberPrivacy(value: String)
object LibraryMemberPrivacy {
  implicit def format[T]: Format[LibraryMemberPrivacy] =
    Format(__.read[String].map(LibraryMemberPrivacy(_)), new Writes[LibraryMemberPrivacy]{ def writes(o: LibraryMemberPrivacy) = JsString(o.value) })
  val NO_ACCESS = LibraryMemberPrivacy("no_access")
  val READ_ONLY = LibraryMemberPrivacy("read_only")
  val READ_WRITE = LibraryMemberPrivacy("read_write")
}
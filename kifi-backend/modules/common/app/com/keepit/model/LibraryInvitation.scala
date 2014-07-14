package com.keepit.model

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

case class LibraryInvitation(
    id: Option[Id[LibraryInvitation]] = None,
    libraryId: Id[Library],
    ownerId: Id[User],
    userId: Id[User],
    access: LibraryAccess,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[LibraryInvitation] = LibraryInvitationStates.ACTIVE,
    seq: SequenceNumber[LibraryInvitation] = SequenceNumber.ZERO) extends ModelWithState[LibraryInvitation] with ModelWithSeqNumber[LibraryInvitation] {

  def withId(id: Id[LibraryInvitation]): LibraryInvitation = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): LibraryInvitation = this.copy(updatedAt = now)
  def withState(newState: State[LibraryInvitation]): LibraryInvitation = this.copy(state = newState)

  override def toString: String = s"LibraryInvitation[id=$id,libraryId=$libraryId,ownerId=$ownerId,userId=$userId,access=$access,state=$state]"
}

object LibraryInvitation {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[LibraryInvitation]) and
    (__ \ 'libraryId).format[Id[Library]] and
    (__ \ 'ownerId).format[Id[User]] and
    (__ \ 'userId).format[Id[User]] and
    (__ \ 'access).format[LibraryAccess] and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[LibraryInvitation]) and
    (__ \ 'seq).format(SequenceNumber.format[LibraryInvitation])
  )(LibraryInvitation.apply, unlift(LibraryInvitation.unapply))
}

case class LibraryInvitationIdKey(id: Id[LibraryInvitation]) extends Key[LibraryInvitation] {
  override val version = 0
  val namespace = "library_Invitation_by_id"
  def toKey(): String = id.id.toString
}
class LibraryInvitationIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibraryInvitationIdKey, LibraryInvitation](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object LibraryInvitationStates extends States[LibraryInvitation]

package com.keepit.model

import javax.crypto.spec.IvParameterSpec

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.crypto.{ ModelWithPublicIdCompanion, ModelWithPublicId }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

case class LibraryInvite(
    id: Option[Id[LibraryInvite]] = None,
    libraryId: Id[Library],
    ownerId: Id[User],
    userId: Id[User],
    access: LibraryAccess,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[LibraryInvite] = LibraryInviteStates.ACTIVE) extends ModelWithPublicId[LibraryInvite] with ModelWithState[LibraryInvite] {

  def withId(id: Id[LibraryInvite]): LibraryInvite = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): LibraryInvite = this.copy(updatedAt = now)
  def withState(newState: State[LibraryInvite]): LibraryInvite = this.copy(state = newState)

  override def toString: String = s"LibraryInvite[id=$id,libraryId=$libraryId,ownerId=$ownerId,userId=$userId,access=$access,state=$state]"
}

object LibraryInvite extends ModelWithPublicIdCompanion[LibraryInvite] {

  protected[this] val publicIdPrefix = "l"
  protected[this] val publicIdIvSpec = new IvParameterSpec(Array(-20, -76, -59, 85, 85, -2, 72, 61, 58, 38, 60, -2, -128, 79, 9, -87))

  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[LibraryInvite]) and
    (__ \ 'libraryId).format[Id[Library]] and
    (__ \ 'ownerId).format[Id[User]] and
    (__ \ 'userId).format[Id[User]] and
    (__ \ 'access).format[LibraryAccess] and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[LibraryInvite])
  )(LibraryInvite.apply, unlift(LibraryInvite.unapply))
}

case class LibraryInviteIdKey(id: Id[LibraryInvite]) extends Key[LibraryInvite] {
  val namespace = "library_invite_by_id"
  def toKey(): String = id.id.toString
}
class LibraryInviteIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibraryInviteIdKey, LibraryInvite](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object LibraryInviteStates extends States[LibraryInvite] {
  val ACCEPTED = State[LibraryInvite]("accepted")
  val DECLINED = State[LibraryInvite]("declined")
}

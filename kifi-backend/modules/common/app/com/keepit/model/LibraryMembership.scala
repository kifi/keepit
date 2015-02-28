package com.keepit.model

import com.keepit.common.cache._
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.keepit.model.view.LibraryMembershipView
import com.kifi.macros.json
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
    showInSearch: Boolean = true,
    listed: Boolean = true, // whether library appears on user's profile
    lastViewed: Option[DateTime] = None,
    lastEmailSent: Option[DateTime] = None,
    lastJoinedAt: Option[DateTime] = None) extends ModelWithState[LibraryMembership] with ModelWithSeqNumber[LibraryMembership] {

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

object LibraryMembership {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[LibraryMembership]) and
    (__ \ 'libraryId).format[Id[Library]] and
    (__ \ 'userId).format[Id[User]] and
    (__ \ 'access).format[LibraryAccess] and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[LibraryMembership]) and
    (__ \ 'seq).format(SequenceNumber.format[LibraryMembership]) and
    (__ \ 'showInSearch).format[Boolean] and
    (__ \ 'listed).format[Boolean] and
    (__ \ 'lastViewed).formatNullable[DateTime] and
    (__ \ 'lastEmailSent).formatNullable[DateTime] and
    (__ \ 'lastJoinedAt).formatNullable[DateTime]
  )(LibraryMembership.apply, unlift(LibraryMembership.unapply))
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

case class LibraryMembershipIdKey(id: Id[LibraryMembership]) extends Key[LibraryMembership] {
  override val version = 2
  val namespace = "library_membership_by_id"
  def toKey(): String = id.id.toString
}

class LibraryMembershipIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibraryMembershipIdKey, LibraryMembership](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class LibraryMembershipCountKey(userId: Id[User], access: LibraryAccess) extends Key[Int] {
  override val version = 1
  val namespace = "library_membership_count"
  def toKey(): String = s"$userId:$access"
}

class LibraryMembershipCountCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[LibraryMembershipCountKey, Int](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class FollowersCountKey(userId: Id[User]) extends Key[Int] {
  override val version = 1
  val namespace = "followers_count"
  def toKey(): String = s"$userId"
}

class FollowersCountCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[FollowersCountKey, Int](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class LibrariesWithWriteAccessUserKey(userId: Id[User]) extends Key[Set[Id[Library]]] {
  override val version = 1
  val namespace = "libraries_with_write_access_by_user"
  def toKey(): String = s"$userId"
}

class LibrariesWithWriteAccessCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibrariesWithWriteAccessUserKey, Set[Id[Library]]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class LibraryMembershipCountByLibIdAndAccessKey(libraryId: Id[Library], access: LibraryAccess) extends Key[Int] {
  override val version = 1
  val namespace = "library_membership_count_by_lib_id_and_access"
  def toKey(): String = s"$libraryId:$access"
}

class LibraryMembershipCountByLibIdAndAccessCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[LibraryMembershipCountByLibIdAndAccessKey, Int](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)


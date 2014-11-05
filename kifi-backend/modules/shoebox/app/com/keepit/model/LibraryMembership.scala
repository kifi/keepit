package com.keepit.model

import com.keepit.common.cache.{ PrimitiveCacheImpl, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.{ SequenceNumber, State, Id }
import com.keepit.common.logging.AccessLog
import com.keepit.common.time.DateTimeJsonFormat
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.concurrent.duration.Duration

object LibraryMembershipFormatter {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[LibraryMembership]) and
    (__ \ 'libraryId).format[Id[Library]] and
    (__ \ 'userId).format[Id[User]] and
    (__ \ 'access).format[LibraryAccess] and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[LibraryMembership]) and
    (__ \ 'seq).format(SequenceNumber.format[LibraryMembership]) and
    (__ \ 'showInSearch).format[Boolean] and
    (__ \ 'lastViewed).formatNullable[DateTime] and
    (__ \ 'lastEmailSent).formatNullable[DateTime]
  )(LibraryMembership.apply, unlift(LibraryMembership.unapply))
}

import LibraryMembershipFormatter.format

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


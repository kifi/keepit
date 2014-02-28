package com.keepit.model

import scala.concurrent.duration._

import org.joda.time.DateTime

import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key, CacheStatistics}
import com.keepit.common.logging.AccessLog
import com.keepit.common.db._
import com.keepit.common.time._

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Collection(
  id: Option[Id[Collection]] = None,
  externalId: ExternalId[Collection] = ExternalId(),
  userId: Id[User],
  name: String,
  state: State[Collection] = CollectionStates.ACTIVE,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  lastKeptTo: Option[DateTime] = None,
  seq: SequenceNumber[Collection] = SequenceNumber.ZERO
  ) extends ModelWithExternalId[Collection] with ModelWithState[Collection] with ModelWithSeqNumber[Collection]{
  def withLastKeptTo(now: DateTime) = this.copy(lastKeptTo = Some(now))
  def withId(id: Id[Collection]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == CollectionStates.ACTIVE
}

object Collection {
  implicit def collectionFormat = (
    (__ \ 'id).formatNullable(Id.format[Collection]) and
    (__ \ 'externalId).format(ExternalId.format[Collection]) and
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'name).format[String] and
    (__ \ 'state).format(State.format[Collection]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'lastKeptTo).formatNullable[DateTime] and
    (__ \ 'seq).format(SequenceNumber.format[Collection])
  )(Collection.apply, unlift(Collection.unapply))

  val MaxNameLength = 64
}

case class SendableTag(
  id: ExternalId[Collection],
  name: String)

object SendableTag {
  private implicit val externalIdFormat = ExternalId.format[Collection]
  implicit val writesSendableTag = Json.writes[SendableTag]

  def from(c: Collection): SendableTag = SendableTag(c.externalId, c.name)
}

case class UserCollectionsKey(userId: Id[User]) extends Key[Seq[Collection]] {
  override val version = 2
  val namespace = "user_collections"
  def toKey(): String = userId.toString
}

class UserCollectionsCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
    extends JsonCacheImpl[UserCollectionsKey, Seq[Collection]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

object CollectionStates extends States[Collection]

package com.keepit.model

import scala.concurrent.duration._

import org.joda.time.DateTime

import com.keepit.common.cache._
import com.keepit.common.logging.AccessLog
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.common.strings._

import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.serializer.BinaryFormat
import java.io.{ ByteArrayInputStream, DataInputStream, DataOutputStream, ByteArrayOutputStream }
import scala.collection.mutable.ListBuffer
import scala.Some

case class Collection(
    id: Option[Id[Collection]] = None,
    externalId: ExternalId[Collection] = ExternalId(),
    userId: Id[User],
    name: String,
    state: State[Collection] = CollectionStates.ACTIVE,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    lastKeptTo: Option[DateTime] = None,
    seq: SequenceNumber[Collection] = SequenceNumber.ZERO) extends ModelWithExternalId[Collection] with ModelWithState[Collection] with ModelWithSeqNumber[Collection] {
  def withLastKeptTo(now: DateTime) = this.copy(lastKeptTo = Some(now))
  def withId(id: Id[Collection]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == CollectionStates.ACTIVE
  def summary: CollectionSummary = CollectionSummary(id.getOrElse(throw new Exception(s"Id for $this is not defined")), externalId, name)
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

case class CollectionSummary(id: Id[Collection], externalId: ExternalId[Collection], name: String)

class CollectionSummariesFormat extends BinaryFormat[Seq[CollectionSummary]] {

  override def writes(prefix: Byte, collections: Seq[CollectionSummary]): Array[Byte] = {
    // 60 is an estimate of the size of a single object (and a bit more).
    // Not sure it would worth it to do an accurate calculation, to be revised
    val byteStream = new ByteArrayOutputStream(60 * collections.size)

    byteStream.write(prefix)
    val outStream = new DataOutputStream(byteStream)

    collections foreach { collection =>
      outStream.writeLong(collection.id.id) //2 bytes
      outStream.writeUTF(collection.externalId.id)
      outStream.writeUTF(collection.name)
    }
    outStream.writeLong(-1)
    outStream.close()
    byteStream.toByteArray
  }

  override def reads(bytes: Array[Byte], offset: Int, length: Int): Seq[CollectionSummary] = {
    val inStream = new DataInputStream(new ByteArrayInputStream(bytes, offset, length))
    val collections = ListBuffer[CollectionSummary]()
    var idLong = inStream.readLong()
    while (idLong > -1) {
      val externalIdString = inStream.readUTF()
      val nameString = inStream.readUTF()

      collections.append(CollectionSummary(Id[Collection](idLong), ExternalId[Collection](externalIdString), nameString))

      idLong = inStream.readLong()
    }
    collections
  }
}

object CollectionSummary {
  implicit def collectionSummariesFormat = new CollectionSummariesFormat()
}

case class SendableTag(
  id: ExternalId[Collection],
  name: String)

object SendableTag {
  private implicit val externalIdFormat = ExternalId.format[Collection]
  implicit val writesSendableTag = Json.writes[SendableTag]

  def from(c: CollectionSummary): SendableTag = SendableTag(c.externalId, c.name)
}

case class UserCollectionsKey(userId: Id[User]) extends Key[Seq[Collection]] {
  override val version = 3
  val namespace = "user_collections"
  def toKey(): String = userId.toString
}

class UserCollectionsCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserCollectionsKey, Seq[Collection]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class UserCollectionSummariesKey(userId: Id[User]) extends Key[Seq[CollectionSummary]] {
  override val version = 2
  val namespace = "user_collection_summaries"
  def toKey(): String = userId.toString
}

class UserCollectionSummariesCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[UserCollectionSummariesKey, Seq[CollectionSummary]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object CollectionStates extends States[Collection]


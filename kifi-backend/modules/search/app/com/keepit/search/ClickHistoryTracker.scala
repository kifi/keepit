package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.common.store._
import com.amazonaws.services.s3.AmazonS3
import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.store.S3Bucket
import com.keepit.common.cache.{BinaryCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key}
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration

case class ClickedURI(id: Id[NormalizedURI])
case class ClickHistoryBuilder (tableSize: Int, numHashFuncs: Int, minHits: Int) extends MultiHashFilterBuilder[ClickedURI]

@ImplementedBy(classOf[ClickHistoryTrackerImpl])
trait ClickHistoryTracker extends MultiHashTracker[Id[User], ClickedURI]

@Singleton
class ClickHistoryTrackerImpl @Inject() (val store: ClickHistoryStore, val builder: ClickHistoryBuilder)
  extends StoreBackedMultiHashTracker[Id[User], ClickedURI] with ClickHistoryTracker {
  def eventToKey(clicked: ClickedURI) = clicked.id.id
}

trait ClickHistoryStore extends ObjectStore[Id[User], MultiHashFilter[ClickedURI]]

class S3ClickHistoryStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val cache: ClickHistoryUserIdCache, val format: ClickHistoryBuilder)
  extends S3BlobStore[Id[User], MultiHashFilter[ClickedURI]] with BlobFormat[MultiHashFilter[ClickedURI]] with ClickHistoryStore {

  protected def idToKey(userId: Id[User]) : String = "click_history_by_userid_" + userId

  override def += (kv: (Id[User], MultiHashFilter[ClickedURI])) = {
    super.+=(kv)
    cache.set(ClickHistoryUserIdKey(kv._1), kv._2)
    this
  }

  override def -= (key: Id[User]) = {
    super.-=(key)
    cache.remove(ClickHistoryUserIdKey(key))
    this
  }

  override def get(id: Id[User]): Option[MultiHashFilter[ClickedURI]] = cache.getOrElseOpt(ClickHistoryUserIdKey(id))(super.get(id))
}

case class ClickHistoryUserIdKey(userId: Id[User]) extends Key[MultiHashFilter[ClickedURI]] {
  override val version = 3
  val namespace = "click_history_by_userid"
  def toKey(): String = userId.id.toString
}

class ClickHistoryUserIdCache(format: ClickHistoryBuilder, stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[ClickHistoryUserIdKey, MultiHashFilter[ClickedURI]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)(format)

class InMemoryClickHistoryStoreImpl extends InMemoryObjectStore[Id[User], MultiHashFilter[ClickedURI]] with ClickHistoryStore

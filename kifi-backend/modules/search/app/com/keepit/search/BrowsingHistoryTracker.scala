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

case class BrowsedURI(id: Id[NormalizedURI])
case class BrowsingHistoryBuilder (tableSize: Int, numHashFuncs: Int, minHits: Int) extends MultiHashFilterBuilder[BrowsedURI]

@ImplementedBy(classOf[BrowsingHistoryTrackerImpl])
trait BrowsingHistoryTracker extends MultiHashTracker[Id[User], BrowsedURI]

@Singleton
class BrowsingHistoryTrackerImpl @Inject() (val store: BrowsingHistoryStore, val builder: BrowsingHistoryBuilder)
  extends StoreBackedMultiHashTracker[Id[User], BrowsedURI] with BrowsingHistoryTracker {
  def eventToKey(browsed: BrowsedURI) = browsed.id.id
}

trait BrowsingHistoryStore extends ObjectStore[Id[User], MultiHashFilter[BrowsedURI]]

class S3BrowsingHistoryStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val cache: BrowsingHistoryUserIdCache, val format: BrowsingHistoryBuilder)
  extends S3BlobStore[Id[User], MultiHashFilter[BrowsedURI]] with BlobFormat[MultiHashFilter[BrowsedURI]] with BrowsingHistoryStore {

  protected def idToKey(userId: Id[User]) : String = "browsing_history_by_userid_" + userId

  override def += (kv: (Id[User], MultiHashFilter[BrowsedURI])) = {
    super.+=(kv)
    cache.set(BrowsingHistoryUserIdKey(kv._1), kv._2)
    this
  }

  override def -= (key: Id[User]) = {
    super.-=(key)
    cache.remove(BrowsingHistoryUserIdKey(key))
    this
  }

  override def get(id: Id[User]): Option[MultiHashFilter[BrowsedURI]] = cache.getOrElseOpt(BrowsingHistoryUserIdKey(id))(super.get(id))
}

case class BrowsingHistoryUserIdKey(userId: Id[User]) extends Key[MultiHashFilter[BrowsedURI]] {
  override val version = 4
  val namespace = "browsing_history_by_userid"
  def toKey(): String = userId.id.toString
}

class BrowsingHistoryUserIdCache(format: BrowsingHistoryBuilder, stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends BinaryCacheImpl[BrowsingHistoryUserIdKey, MultiHashFilter[BrowsedURI]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)(format)

class InMemoryBrowsingHistoryStoreImpl extends InMemoryObjectStore[Id[User], MultiHashFilter[BrowsedURI]] with BrowsingHistoryStore

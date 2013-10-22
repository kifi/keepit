package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.common.store._
import com.amazonaws.services.s3.AmazonS3
import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.store.S3Bucket

case class BrowsedURI(id: Id[NormalizedURI])
case class BrowsingHistoryBuilder (tableSize: Int, numHashFuncs: Int, minHits: Int) extends MultiHashFilterBuilder[BrowsedURI]

@ImplementedBy(classOf[BrowsingHistoryTrackerImpl])
trait BrowsingHistoryTracker extends MultiHashTracker[Id[User], BrowsedURI]

@Singleton
class BrowsingHistoryTrackerImpl @Inject() (val store: BrowsingHistoryStore, val builder: BrowsingHistoryBuilder) extends StoreBackedMultiHashTracker[Id[User], BrowsedURI] with BrowsingHistoryTracker {
  def eventToKey(browsed: BrowsedURI) = browsed.id.id
}

trait BrowsingHistoryStore extends ObjectStore[Id[User], MultiHashFilter[BrowsedURI]]

class S3BrowsingHistoryStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val format: BrowsingHistoryBuilder)
  extends S3BlobStore[Id[User], MultiHashFilter[BrowsedURI]] with BlobFormat[MultiHashFilter[BrowsedURI]] with BrowsingHistoryStore {

  protected def idToKey(userId: Id[User]) : String = "browsing_history_by_userid_" + userId
}

class InMemoryBrowsingHistoryStoreImpl extends InMemoryObjectStore[Id[User], MultiHashFilter[BrowsedURI]] with BrowsingHistoryStore

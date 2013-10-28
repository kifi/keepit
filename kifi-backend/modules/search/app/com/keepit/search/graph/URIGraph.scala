package com.keepit.search.graph

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db._
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.akka.MonitoredAwait

@ImplementedBy(classOf[URIGraphImpl])
trait URIGraph {
  def update(): Int
  def update(userId: Id[User]): Int
  def reindex(): Unit
  def reindexCollection(): Unit
  def getURIGraphSearcher(): URIGraphSearcher
  def getURIGraphSearcher(userId: Id[User]): URIGraphSearcherWithUser
  def getCollectionSearcher(): CollectionSearcher
  def getCollectionSearcher(userId: Id[User]): CollectionSearcherWithUser
  def close(): Unit
}

@Singleton
class URIGraphImpl @Inject()(
    val uriGraphIndexer: URIGraphIndexer,
    val collectionIndexer: CollectionIndexer,
    shoeboxClient: ShoeboxServiceClient,
    monitoredAwait: MonitoredAwait
  ) extends URIGraph {

  def update(): Int = {
    collectionIndexer.update()
    uriGraphIndexer.update()
  }
  def update(userId: Id[User]): Int = {
    collectionIndexer.update(userId)
    uriGraphIndexer.update(userId)
  }
  def reindex() {
    collectionIndexer.reindex()
    uriGraphIndexer.reindex()
  }
  def reindexCollection(): Unit = collectionIndexer.reindex()

  def close() {
    collectionIndexer.close()
    uriGraphIndexer.close()
  }

  def getURIGraphSearcher(): URIGraphSearcher = {
    val (indexSearcher, storeSearcher) = uriGraphIndexer.getSearchers
    new URIGraphSearcher(indexSearcher, storeSearcher)
  }

  def getURIGraphSearcher(userId: Id[User]): URIGraphSearcherWithUser = {
    val (indexSearcher, storeSearcher) = uriGraphIndexer.getSearchers
    new URIGraphSearcherWithUser(indexSearcher, storeSearcher, userId, shoeboxClient, monitoredAwait)
  }

  def getCollectionSearcher(): CollectionSearcher = {
    new CollectionSearcher(collectionIndexer.getSearcher)
  }

  def getCollectionSearcher(userId: Id[User]): CollectionSearcherWithUser = {
    val (collectionIndexSearcher, collectionNameIndexSearcher) = collectionIndexer.getSearchers
    new CollectionSearcherWithUser(collectionIndexSearcher, collectionNameIndexSearcher, userId)
  }
}

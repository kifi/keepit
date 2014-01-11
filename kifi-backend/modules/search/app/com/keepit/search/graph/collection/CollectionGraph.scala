package com.keepit.search.graph.collection

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db._
import com.keepit.model._

@ImplementedBy(classOf[CollectionGraphImpl])
trait CollectionGraph {
  def getCollectionSearcher(): CollectionSearcher
  def getCollectionSearcher(userId: Id[User]): CollectionSearcherWithUser
}

@Singleton
class CollectionGraphImpl @Inject()(
  val collectionIndexer: CollectionIndexer
) extends CollectionGraph {

  def getCollectionSearcher(): CollectionSearcher = new CollectionSearcher(collectionIndexer.getSearcher)

  def getCollectionSearcher(userId: Id[User]): CollectionSearcherWithUser = {
    val (collectionIndexSearcher, collectionNameIndexSearcher) = collectionIndexer.getSearchers
    new CollectionSearcherWithUser(collectionIndexSearcher, collectionNameIndexSearcher, userId)
  }

}

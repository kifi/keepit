package com.keepit.search.graph.collection

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db._
import com.keepit.model._

@ImplementedBy(classOf[CollectionGraphImpl])
trait CollectionGraph {
  def update(): Int
  def update(userId: Id[User]): Int
  def reindex(): Unit
  def getCollectionSearcher(): CollectionSearcher
  def getCollectionSearcher(userId: Id[User]): CollectionSearcherWithUser
  def close(): Unit
  def backup(): Unit
}

@Singleton
class CollectionGraphImpl @Inject()(
  val collectionIndexer: CollectionIndexer
) extends CollectionGraph {
    def update(): Int = collectionIndexer.update()

  def update(userId: Id[User]): Int = collectionIndexer.update(userId)

  def reindex(): Unit = collectionIndexer.reindex()

  def close(): Unit = collectionIndexer.close()

  def backup(): Unit = collectionIndexer.backup()

  def getCollectionSearcher(): CollectionSearcher = new CollectionSearcher(collectionIndexer.getSearcher)

  def getCollectionSearcher(userId: Id[User]): CollectionSearcherWithUser = {
    val (collectionIndexSearcher, collectionNameIndexSearcher) = collectionIndexer.getSearchers
    new CollectionSearcherWithUser(collectionIndexSearcher, collectionNameIndexSearcher, userId)
  }

}

package com.keepit.search.graph

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db._
import com.keepit.model._

@ImplementedBy(classOf[URIGraphImpl])
trait URIGraph {
  def update(): Int
  def update(userId: Id[User]): Int
  def reindex(): Unit
  def getURIGraphSearcher(userId: Option[Id[User]] = None): URIGraphSearcher
  def close(): Unit
}

@Singleton
class URIGraphImpl @Inject()(
    val uriGraphIndexer: URIGraphIndexer,
    val collectionIndexer: CollectionIndexer
  ) extends URIGraph {

  def update(): Int = {
    uriGraphIndexer.update()
  }
  def update(userId: Id[User]): Int = {
    uriGraphIndexer.update(userId)
  }
  def reindex() {
    uriGraphIndexer.reindex()
  }
  def close() {
    uriGraphIndexer.close()
  }
  def getURIGraphSearcher(userId: Option[Id[User]]): URIGraphSearcher = {
    new URIGraphSearcher(uriGraphIndexer.getSearcher, userId)
  }
}

package com.keepit.search.graph

import com.keepit.common.db._
import com.keepit.model._

trait URIGraph {
  def update(): Int
  def update(userId: Id[User]): Int
  def reindex(): Unit
  def getURIGraphSearcher(userId: Option[Id[User]] = None): URIGraphSearcher
  def close(): Unit
}

class URIGraphImpl(val uriGraphIndexer: URIGraphIndexer) extends URIGraph {
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

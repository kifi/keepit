package com.keepit.search.graph

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db._
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.akka.MonitoredAwait
import com.keepit.search.graph.collection._
import com.keepit.search.graph.bookmark._

@ImplementedBy(classOf[URIGraphImpl])
trait URIGraph {
  def getURIGraphSearcher(): URIGraphSearcher
  def getURIGraphSearcher(userId: Id[User]): URIGraphSearcherWithUser

}

@Singleton
class URIGraphImpl @Inject()(
    val uriGraphIndexer: URIGraphIndexer,
    shoeboxClient: ShoeboxServiceClient,
    monitoredAwait: MonitoredAwait
  ) extends URIGraph {

  def getURIGraphSearcher(): URIGraphSearcher = {
    val (indexSearcher, storeSearcher) = uriGraphIndexer.getSearchers
    new URIGraphSearcher(indexSearcher, storeSearcher)
  }

  def getURIGraphSearcher(userId: Id[User]): URIGraphSearcherWithUser = {
    val (indexSearcher, storeSearcher) = uriGraphIndexer.getSearchers
    new URIGraphSearcherWithUser(indexSearcher, storeSearcher, userId, shoeboxClient, monitoredAwait)
  }

}

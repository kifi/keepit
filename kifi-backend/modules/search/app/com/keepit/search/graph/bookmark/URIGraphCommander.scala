package com.keepit.search.graph.bookmark

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.model.User
import com.keepit.search.graph.URIList
import com.keepit.search.sharding.Shard
import com.keepit.search.sharding.ShardedURIGraphIndexer

case class UserURIList(publicList: Option[URIList], privateList: Option[URIList])

@ImplementedBy(classOf[URIGraphCommanderImpl])
trait URIGraphCommander{
  def getIndexShards: Seq[Shard[NormalizedURI]]
  def getIndexer(shard: Shard[NormalizedURI]): URIGraphIndexer
  def getUserUriList(userId: Id[User], publicOnly: Boolean): Map[Shard[NormalizedURI], UserURIList]
  def getUserUriList(userId: Id[User], publicOnly: Boolean, shard: Shard[NormalizedURI]): UserURIList
  def getUserUriLists(userIds: Set[Id[User]], publicOnly: Boolean, shard: Shard[NormalizedURI]): Map[Id[User], UserURIList]
}

@Singleton
class URIGraphCommanderImpl @Inject()(
  shardedUriGraphIndexer: ShardedURIGraphIndexer
) extends URIGraphCommander{

  def getIndexShards = shardedUriGraphIndexer.indexShards.keys.toSeq
  def getIndexer(shard: Shard[NormalizedURI]) = shardedUriGraphIndexer.indexShards.get(shard).get

  def getShardedIndexers = shardedUriGraphIndexer.indexShards

  private def getURIList(userId: Id[User], publicOnly: Boolean, searcher: URIGraphSearcher): UserURIList = {
    val edgeSet = searcher.getUserToUriEdgeSet(userId, publicOnly)
    UserURIList(edgeSet.getPublicList, edgeSet.getPrivateList)
  }

  def getUserUriList(userId: Id[User], publicOnly: Boolean): Map[Shard[NormalizedURI], UserURIList] = {
    getIndexShards.map{shard => shard -> getUserUriList(userId, publicOnly, shard)}.toMap
  }

  def getUserUriList(userId: Id[User], publicOnly: Boolean, shard: Shard[NormalizedURI]): UserURIList = {
    shardedUriGraphIndexer.indexShards.get(shard) match {
      case Some(indexer) =>
        val searcher = URIGraphSearcher(indexer)
        getURIList(userId, publicOnly, searcher)
      case None => throw new Exception(s"normalizedURI shard not found ${shard.shardId}")
    }
  }

  def getUserUriLists(userIds: Set[Id[User]], publicOnly: Boolean, shard: Shard[NormalizedURI]): Map[Id[User], UserURIList] = {
    shardedUriGraphIndexer.indexShards.get(shard) match {
      case Some(indexer) =>
        val searcher = URIGraphSearcher(indexer)
        userIds.foldLeft(Map.empty[Id[User], UserURIList]){ case (m, userId) => m + (userId -> getURIList(userId, publicOnly, searcher)) }
      case None => throw new Exception(s"normalizedURI shard not found ${shard.shardId}")
    }
  }
}

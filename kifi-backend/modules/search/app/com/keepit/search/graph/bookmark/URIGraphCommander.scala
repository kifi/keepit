package com.keepit.search.graph.bookmark

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.model.User
import com.keepit.search.graph.URIList
import com.keepit.search.sharding.Shard
import com.keepit.search.sharding.ShardedURIGraphIndexer

case class UserURIList(publicList: Option[URIList], privateList: Option[URIList])
case class RequestingUser(userId: Id[User])

class NotAuthorizedURIGraphQueryException(msg: String) extends Exception(msg)

class URIGraphCommanderFactory @Inject()(shardedUriGraphIndexer: ShardedURIGraphIndexer) {
  def apply(userId: Id[User]): URIGraphCommander = {
    new URIGraphCommanderImpl(RequestingUser(userId), shardedUriGraphIndexer)
  }
}

trait URIGraphCommander{
  val requestingUser: RequestingUser
  def getIndexShards: Seq[Shard[NormalizedURI]]
  def getIndexer(shard: Shard[NormalizedURI]): URIGraphIndexer
  def getUserUriList(userId: Id[User], publicOnly: Boolean): Map[Shard[NormalizedURI], UserURIList]
  def getUserUriList(userId: Id[User], publicOnly: Boolean, shard: Shard[NormalizedURI]): UserURIList
  def getUserUriLists(userIds: Set[Id[User]], publicOnly: Boolean, shard: Shard[NormalizedURI]): Map[Id[User], UserURIList]
}

class URIGraphCommanderImpl(
  override val requestingUser: RequestingUser,
  shardedUriGraphIndexer: ShardedURIGraphIndexer
) extends URIGraphCommander{

  def getIndexShards = shardedUriGraphIndexer.indexShards.keys.toSeq
  def getIndexer(shard: Shard[NormalizedURI]) = shardedUriGraphIndexer.indexShards.get(shard).get

  def getShardedIndexers = shardedUriGraphIndexer.indexShards

  private def getURIList(userId: Id[User], publicOnly: Boolean, searcher: URIGraphSearcher): UserURIList = {
    if (publicOnly != true && requestingUser.userId != userId) throw new NotAuthorizedURIGraphQueryException(s"requesting user ${requestingUser.userId} should not have access to user ${userId}'s private keeps")
    val edgeSet = searcher.getUserToUriEdgeSet(userId, publicOnly)
    UserURIList(edgeSet.getPublicList, edgeSet.getPrivateList)
  }

  def getUserUriList(userId: Id[User], publicOnly: Boolean): Map[Shard[NormalizedURI], UserURIList] = {
    if (publicOnly != true && requestingUser.userId != userId) throw new NotAuthorizedURIGraphQueryException(s"requesting user ${requestingUser.userId} should not have access to user ${userId}'s private keeps")
    getIndexShards.map{shard => shard -> getUserUriList(userId, publicOnly, shard)}.toMap
  }

  def getUserUriList(userId: Id[User], publicOnly: Boolean, shard: Shard[NormalizedURI]): UserURIList = {
    if (publicOnly != true && requestingUser.userId != userId) throw new NotAuthorizedURIGraphQueryException(s"requesting user ${requestingUser.userId} should not have access to user ${userId}'s private keeps")
    shardedUriGraphIndexer.indexShards.get(shard) match {
      case Some(indexer) =>
        val searcher = URIGraphSearcher(indexer)
        getURIList(userId, publicOnly, searcher)
      case None => throw new Exception(s"normalizedURI shard not found ${shard.shardId}")
    }
  }

  def getUserUriLists(userIds: Set[Id[User]], publicOnly: Boolean, shard: Shard[NormalizedURI]): Map[Id[User], UserURIList] = {
    if (publicOnly != true && userIds.exists( _ != requestingUser.userId)) throw new NotAuthorizedURIGraphQueryException(s"requesting user ${requestingUser.userId} should not have access to other user's private keeps")
    shardedUriGraphIndexer.indexShards.get(shard) match {
      case Some(indexer) =>
        val searcher = URIGraphSearcher(indexer)
        userIds.foldLeft(Map.empty[Id[User], UserURIList]){ case (m, userId) => m + (userId -> getURIList(userId, publicOnly, searcher)) }
      case None => throw new Exception(s"normalizedURI shard not found ${shard.shardId}")
    }
  }
}

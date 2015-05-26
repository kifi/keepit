package com.keepit.search

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.concurrent.ExecutionContext.fj
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.service.RequestConsolidator
import com.keepit.model.{ Collection, Library, NormalizedURI, User }
import com.keepit.search.engine.Visibility
import com.keepit.search.engine.uri.{ UriShardHit, UriShardResult }
import com.keepit.search.index.Searcher
import com.keepit.search.index.graph.collection.{ CollectionSearcher, CollectionSearcherWithUser }
import com.keepit.search.index.graph.library.LibraryIndexer
import com.keepit.search.result._
import com.keepit.search.index.sharding.{ ShardedCollectionIndexer, Shard }
import com.google.inject.{ Singleton, Inject }
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import com.keepit.search.augmentation.{ ItemAugmentationRequest, AugmentableItem, AugmentedItem, AugmentationCommander }

@Singleton
class SearchBackwardCompatibilitySupport @Inject() (
    libraryIndexer: LibraryIndexer,
    augmentationCommander: AugmentationCommander,
    shardedCollectionIndexer: ShardedCollectionIndexer,
    monitoredAwait: MonitoredAwait) {

  implicit private[this] val defaultExecutionContext = fj

  private[this] val collectionSearcherReqConsolidator = new RequestConsolidator[(Shard[NormalizedURI], Id[User]), CollectionSearcherWithUser](3 seconds)

  private def getCollectionSearcherFuture(shard: Shard[NormalizedURI], userId: Id[User]) = collectionSearcherReqConsolidator((shard, userId)) {
    case (shard, userId) =>
      Future.successful(CollectionSearcher(userId, shardedCollectionIndexer.getIndexer(shard)))
  }

  private def getCollectionSearcher(shard: Shard[NormalizedURI], userId: Id[User]): CollectionSearcherWithUser = {
    Await.result(getCollectionSearcherFuture(shard, userId), 5 seconds)
  }

  private def getCollectionExternalIds(shard: Shard[NormalizedURI], userId: Id[User], uriId: Id[NormalizedURI]): Option[Seq[ExternalId[Collection]]] = {
    val collectionSearcher = getCollectionSearcher(shard, userId)
    val collIds = collectionSearcher.intersect(collectionSearcher.myCollectionEdgeSet, collectionSearcher.getUriToCollectionEdgeSet(uriId)).destIdLongSet
    if (collIds.isEmpty) None else Some(collIds.toSeq.sortBy(0L - _).map { id => collectionSearcher.getExternalId(id) }.collect { case Some(extId) => extId })
  }

  def toDetailedSearchHit(shards: Set[Shard[NormalizedURI]], userId: Id[User], hit: UriShardHit, augmentedItem: AugmentedItem, friendStats: FriendStats, librarySearcher: Searcher): DetailedSearchHit = {
    val uriId = augmentedItem.uri
    val isMyBookmark = ((hit.visibility & (Visibility.OWNER | Visibility.MEMBER)) != 0)
    val isFriendsBookmark = ((hit.visibility & Visibility.NETWORK) != 0)

    shards.find(_.contains(uriId)) match {
      case Some(shard) =>

        val basicSearchHit = if (isMyBookmark) {
          BasicSearchHit(Some(hit.title), hit.url, getCollectionExternalIds(shard, userId, uriId), hit.externalId)
        } else {
          BasicSearchHit(Some(hit.title), hit.url)
        }

        val friends = augmentedItem.relatedKeepers.map(_._1).filter(_ != userId)
        friends.foreach(friendId => friendStats.add(friendId.id, hit.score))

        val isPrivate = augmentedItem.isSecret(libraryIndexer.getSearcher) getOrElse false

        DetailedSearchHit(
          uriId.id,
          augmentedItem.keepersTotal,
          basicSearchHit,
          isMyBookmark,
          isFriendsBookmark,
          isPrivate,
          friends,
          hit.score,
          hit.score,
          new Scoring(hit.score, 0.0f, 0.0f, 0.0f, false)
        )

      case None =>
        throw new Exception("shard not found")
    }
  }

  def toPartialSearchResult(shards: Set[Shard[NormalizedURI]], userId: Id[User], friendIds: Set[Long], result: UriShardResult): PartialSearchResult = {
    val items = result.hits.map { hit => AugmentableItem(Id(hit.id), hit.libraryId.map(Id(_))) }
    val augmentationRequest = ItemAugmentationRequest.uniform(userId, items: _*)
    val friendStats = FriendStats(friendIds)
    val librarySearcher = libraryIndexer.getSearcher

    val future = augmentationCommander.distAugmentation(shards, augmentationRequest).map { augmentationResponse =>
      val augmenter = AugmentedItem(userId, friendIds.map(Id[User](_)), Set.empty[Id[Library]], augmentationResponse.scores) _

      (result.hits zip items).map {
        case (hit, item) =>
          toDetailedSearchHit(shards, userId, hit, augmenter(item, augmentationResponse.infos(item)), friendStats, librarySearcher)
      }
    }

    val detailedSearchHits = monitoredAwait.result(future, 3 seconds, "slow augmentation")
    PartialSearchResult(detailedSearchHits, result.myTotal, result.friendsTotal, result.othersTotal, friendStats, result.show)
  }
}

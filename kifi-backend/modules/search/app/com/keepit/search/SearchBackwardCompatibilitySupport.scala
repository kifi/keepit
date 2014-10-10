package com.keepit.search

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.concurrent.ExecutionContext.fj
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.model.{ Collection, Library, NormalizedURI, User }
import com.keepit.search.engine.Visibility
import com.keepit.search.engine.result.{ KifiShardHit, KifiShardResult }
import com.keepit.search.graph.library.{ LibraryIndexable, LibraryFields, LibraryIndexer }
import com.keepit.search.result._
import com.keepit.search.sharding.Shard
import com.google.inject.Inject
import scala.concurrent.duration._

class SearchBackwardCompatibilitySupport @Inject() (
    libraryIndexer: LibraryIndexer,
    augmentationCommander: AugmentationCommander,
    mainSearcherFactory: MainSearcherFactory,
    monitoredAwait: MonitoredAwait) {

  implicit private[this] val defaultExecutionContext = fj

  private def getCollectionExternalIds(shard: Shard[NormalizedURI], userId: Id[User], uriId: Id[NormalizedURI]): Option[Seq[ExternalId[Collection]]] = {
    val collectionSearcher = mainSearcherFactory.getCollectionSearcher(shard, userId)
    val collIds = collectionSearcher.intersect(collectionSearcher.myCollectionEdgeSet, collectionSearcher.getUriToCollectionEdgeSet(uriId)).destIdLongSet
    if (collIds.isEmpty) None else Some(collIds.toSeq.sortBy(0L - _).map { id => collectionSearcher.getExternalId(id) }.collect { case Some(extId) => extId })
  }

  def toDetailedSearchHit(shards: Set[Shard[NormalizedURI]], userId: Id[User], hit: KifiShardHit, augmentedItem: AugmentedItem, friendStats: FriendStats, librarySearcher: Searcher): DetailedSearchHit = {
    val uriId = augmentedItem.uri
    val isMyBookmark = ((hit.visibility & (Visibility.OWNER | Visibility.MEMBER)) != 0)
    val isFriendsBookmark = (!isMyBookmark && (hit.visibility & Visibility.NETWORK) != 0)

    shards.find(_.contains(uriId)) match {
      case Some(shard) =>

        val basicSearchHit = if (isMyBookmark) {
          BasicSearchHit(Some(hit.title), hit.url, getCollectionExternalIds(shard, userId, uriId), hit.externalId)
        } else {
          BasicSearchHit(Some(hit.title), hit.url)
        }

        val friends = augmentedItem.relatedKeepers.filter(_ != userId)
        friends.foreach(friendId => friendStats.add(friendId.id, hit.score))

        val isPrivate = augmentedItem.isSecret(LibraryIndexable.isSecret(libraryIndexer.getSearcher, _))

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

  def toPartialSearchResult(shards: Set[Shard[NormalizedURI]], userId: Id[User], friendIds: Set[Long], result: KifiShardResult): PartialSearchResult = {
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

package com.keepit.search

import com.keepit.common.akka.SafeFuture
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.graph._
import com.keepit.search.graph.collection.CollectionSearcherWithUser
import com.keepit.search.graph.bookmark.URIGraphSearcherWithUser
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.model.NormalizedURI

class SocialGraphInfo(userId: Id[User], val uriGraphSearcher: URIGraphSearcherWithUser, val collectionSearcher: CollectionSearcherWithUser, filter: SearchFilter, monitoredAwait: MonitoredAwait) {

  lazy val (myUriEdgeAccessor, friendsUriEdgeAccessors, mySearchUris, friendSearchUris, relevantFriendEdgeSet, socialGraphInfoTime) = {
    monitoredAwait.result(socialGraphInfoFuture, 5 seconds, s"getting SocialGraphInfo for user Id $userId")
  }

  private[this] val socialGraphInfoFuture = SafeFuture {
    val startTime: Long = System.currentTimeMillis

    // initialize user's social graph info
    val myUriEdges = uriGraphSearcher.myUriEdgeSet
    val mySearchUris =
      filter.timeRange match {
        case Some(timeRange) =>
          filter.collections match {
            case Some(collections) =>
              collections.foldLeft(Set.empty[Long]){ (s, collId) =>
                s ++ collectionSearcher.getCollectionToUriEdgeSet(collId).filterByTimeRange(timeRange.start, timeRange.end).destIdLongSet
              }
            case _ => myUriEdges.filterByTimeRange(timeRange.start, timeRange.end).destIdLongSet
          }
        // no time range
        case _ =>
          filter.collections match {
            case Some(collections) =>
              collections.foldLeft(Set.empty[Long]){ (s, collId) =>
                s ++ collectionSearcher.getCollectionToUriEdgeSet(collId).destIdLongSet
              }
            case _ => myUriEdges.destIdLongSet
          }
      }

    val friendEdgeSet = uriGraphSearcher.searchFriendEdgeSet
    val friendsUriEdgeSets = uriGraphSearcher.friendsUriEdgeSets
    val (filteredFriendEdgeSet, relevantFriendEdgeSet) = {
      if (filter.isCustom) {
        val fullFriendEdgeSet = uriGraphSearcher.friendEdgeSet
        val filtered = uriGraphSearcher.getUserToUserEdgeSet(userId, filter.filterFriends(fullFriendEdgeSet.destIdSet)) // a custom filter can have non-search friends
        val unioned = uriGraphSearcher.getUserToUserEdgeSet(userId, filtered.destIdSet ++ friendEdgeSet.destIdSet)
        (filtered, unioned)
      } else {
        (friendEdgeSet, friendEdgeSet)
      }
    }
    val friendSearchUris = filter.collections match {
      case Some(colls) =>
        Set.empty[Long]
      case _ =>
        filter.timeRange match {
          case Some(timeRange) =>
            filteredFriendEdgeSet.destIdSet.foldLeft(Set.empty[Long]){ (s, f) =>
              s ++ friendsUriEdgeSets(f.id).filterByTimeRange(timeRange.start, timeRange.end).destIdLongSet
            }
          case _ =>
            filteredFriendEdgeSet.destIdSet.foldLeft(Set.empty[Long]){ (s, f) =>
              s ++ friendsUriEdgeSets(f.id).destIdLongSet
            }
        }
    }

    val time: Long = System.currentTimeMillis - startTime

    (myUriEdges.accessor.asInstanceOf[BookmarkInfoAccessor[User, NormalizedURI]], friendsUriEdgeSets.mapValues{ _.accessor.asInstanceOf[BookmarkInfoAccessor[User, NormalizedURI]] }, mySearchUris, friendSearchUris, relevantFriendEdgeSet, time)
  }
}

package com.keepit.search

import com.keepit.common.akka.SafeFuture
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db.Id
import com.keepit.model.Collection
import com.keepit.model.NormalizedURI
import com.keepit.model.User
import com.keepit.search.graph._
import com.keepit.search.graph.collection.CollectionSearcherWithUser
import com.keepit.search.graph.bookmark.URIGraphSearcherWithUser
import com.keepit.search.util.LongArraySet
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration._
import scala.math.max

class SocialGraphInfo(userId: Id[User], val uriGraphSearcher: URIGraphSearcherWithUser, val collectionSearcher: CollectionSearcherWithUser, filter: SearchFilter, monitoredAwait: MonitoredAwait) {

  private[this] val startTime: Long = System.currentTimeMillis

  lazy val (myUriEdgeAccessor, mySearchUris: LongArraySet, part1End) = {
    monitoredAwait.result(part1, 5 seconds, s"getting SocialGraphInfo.my* for user Id $userId")
  }
  lazy val (friendsUriEdgeAccessors, friendSearchUris: LongArraySet, friendEdgeSet, part2End) = {
    monitoredAwait.result(part2, 5 seconds, s"getting SocialGraphInfo.friends* for user Id $userId")
  }

  lazy val socialGraphInfoTime = max(part1End, part2End) - startTime

  // initialize user's social graph info in two parts

  private[this] val part1 = SafeFuture {
    val myUriEdges = uriGraphSearcher.myUriEdgeSet
    val mySearchUris = myUriEdges.destIdLongSet
    (myUriEdges.accessor.asInstanceOf[BookmarkInfoAccessor[User, NormalizedURI]], LongArraySet.fromSet(mySearchUris), System.currentTimeMillis)
  }

  private[this] val part2 = SafeFuture {
    val friendEdgeSet = uriGraphSearcher.searchFriendEdgeSet
    val friendsUriEdgeSets = uriGraphSearcher.friendsUriEdgeSets
    val friendSearchUris = friendEdgeSet.destIdSet.foldLeft(Set.empty[Long]) { (s, f) => s ++ friendsUriEdgeSets(f.id).destIdLongSet }

    (friendsUriEdgeSets.mapValues { _.accessor.asInstanceOf[BookmarkInfoAccessor[User, NormalizedURI]] }, LongArraySet.fromSet(friendSearchUris), friendEdgeSet, System.currentTimeMillis)
  }
}

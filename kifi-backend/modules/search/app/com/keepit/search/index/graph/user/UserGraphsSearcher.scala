package com.keepit.search.index.graph.user

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.service.RequestConsolidator
import com.keepit.model.User
import scala.concurrent._
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

@Singleton
class UserGraphsSearcherFactory @Inject() (userGraph: UserGraphIndexer, searchFriendGraph: SearchFriendIndexer) {

  private[this] val consolidatedConnectedUsersReq = new RequestConsolidator[Id[User], Set[Long]](3 seconds)
  private[this] val consolidatedUnfriendedReq = new RequestConsolidator[Id[User], Set[Long]](3 seconds)

  def apply(userId: Id[User]) = {
    new UserGraphsSearcher(
      userId,
      userGraph,
      searchFriendGraph,
      consolidatedConnectedUsersReq,
      consolidatedUnfriendedReq)
  }

  def clear(): Unit = {
    consolidatedConnectedUsersReq.clear()
    consolidatedUnfriendedReq.clear()
  }
}

class UserGraphsSearcher(
    val userId: Id[User],
    userGraph: UserGraphIndexer,
    searchFriendGraph: SearchFriendIndexer,
    consolidatedConnectedUsersReq: RequestConsolidator[Id[User], Set[Long]],
    consolidatedUnfriendedReq: RequestConsolidator[Id[User], Set[Long]]) {

  def getConnectedUsersFuture(): Future[Set[Long]] = consolidatedConnectedUsersReq(userId) { _ =>
    Future { new UserGraphSearcher(userGraph.getSearcher).getFriends(userId) }
  }
  def getUnfriendedFuture(): Future[Set[Long]] = consolidatedUnfriendedReq(userId) { _ =>
    Future { new SearchFriendSearcher(searchFriendGraph.getSearcher).getUnfriended(userId) }
  }

  def getSearchFriendsFuture(): Future[Set[Long]] = {
    (getUnfriendedFuture() zip getConnectedUsersFuture()).map {
      case (unfriends, friends) =>
        if (unfriends.isEmpty) friends else (friends -- unfriends)
    }
  }
}

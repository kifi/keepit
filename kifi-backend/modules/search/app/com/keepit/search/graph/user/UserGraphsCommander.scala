package com.keepit.search.graph.user

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.service.RequestConsolidator
import com.keepit.model.User
import scala.concurrent._
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class UserGraphsCommander @Inject()(
  userGraph: UserGraphIndexer,
  searchFriendGraph: SearchFriendIndexer
){
  private[this] val consolidatedConnectedUsersReq = new RequestConsolidator[Id[User], Set[Long]](3 seconds)
  private[this] val consolidatedUnfriendedReq = new RequestConsolidator[Id[User], Set[Long]](3 seconds)

  def getConnectedUsersFuture(id: Id[User]): Future[Set[Long]] = consolidatedConnectedUsersReq(id){ id => Future{ getConnectedUsers(id) } }
  def getUnfriendedFuture(id: Id[User]): Future[Set[Long]] = consolidatedUnfriendedReq(id){ id => Future{ getUnfriended(id) } }

  def getConnectedUsers(id: Id[User]): Set[Long] = {
    val searcher = new UserGraphSearcher(userGraph.getSearcher)
    searcher.getFriends(id)
  }

  def getUnfriended(id: Id[User]): Set[Long] = {
    val searcher = new SearchFriendSearcher(searchFriendGraph.getSearcher)
    searcher.getUnfriended(id)
  }
}

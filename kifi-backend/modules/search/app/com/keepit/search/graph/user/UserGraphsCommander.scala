package com.keepit.search.graph.user

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.model.User

class UserGraphsCommander @Inject()(
  userGraph: UserGraphIndexer,
  searchFriendGraph: SearchFriendIndexer
){

  def getConnectedUsers(id: Id[User]): Set[Long] = {
    val searcher = new UserGraphSearcher(userGraph.getSearcher)
    searcher.getFriends(id)
  }

  def getUnfriended(id: Id[User]): Set[Long] = {
    val searcher = new SearchFriendSearcher(searchFriendGraph.getSearcher)
    searcher.getUnfriended(id)
  }
}

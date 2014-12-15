package com.keepit.search.index.graph.user

import com.keepit.search.Searcher
import com.keepit.search.index.graph.BaseGraphSearcher
import com.keepit.search.index.graph.URIList
import com.keepit.search.util.LongArraySet
import com.keepit.common.db.Id
import com.keepit.model.User
import java.util.Arrays

class UserGraphSearcher(searcher: Searcher) extends BaseGraphSearcher(searcher) {
  import UserGraphFields._

  override def getURIList(field: String, docid: Int): URIList = throw new UnsupportedOperationException

  def getFriends(userId: Id[User]): Set[Long] = {
    val docid = getDocId(userId.id)
    val arr = getLongArray(friendsList, docid)
    Arrays.sort(arr)
    LongArraySet.fromSorted(arr)
  }
}

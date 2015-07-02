package com.keepit.search.index.graph.user

import com.keepit.search.index.Searcher
import com.keepit.search.index.graph.BaseGraphSearcher
import com.keepit.search.util.LongArraySet
import com.keepit.common.db.Id
import com.keepit.model.User
import java.util.Arrays

// Actually search for unfriended
class SearchFriendSearcher(searcher: Searcher) extends BaseGraphSearcher(searcher) {
  import SearchFriendFields._

  def getUnfriended(userId: Id[User]): Set[Long] = {
    val docid = getDocId(userId.id)
    val arr = getLongArray(unfriendedList, docid)
    Arrays.sort(arr)
    LongArraySet.fromSorted(arr)
  }
}

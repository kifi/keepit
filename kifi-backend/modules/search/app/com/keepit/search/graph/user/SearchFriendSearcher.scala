package com.keepit.search.graph.user

import com.keepit.search.Searcher
import com.keepit.search.graph.BaseGraphSearcher
import com.keepit.search.graph.URIList
import com.keepit.common.db.Id
import com.keepit.model.User
import org.apache.lucene.util.BytesRef
import com.keepit.search.graph.Util

// Actually search for unfriended
class SearchFriendSearcher(searcher: Searcher) extends BaseGraphSearcher(searcher) {
  import SearchFriendFields._

  override def getURIList(field: String, docid: Int): URIList = throw new UnsupportedOperationException

  def getUnfriended(userId: Id[User]): Set[Long] = {
    val docid = getDocId(userId.id)
    getLongArray(unfriendedList, docid).toSet
  }
}

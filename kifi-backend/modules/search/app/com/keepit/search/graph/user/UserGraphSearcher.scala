package com.keepit.search.graph.user

import com.keepit.search.Searcher
import com.keepit.search.graph.BaseGraphSearcher
import com.keepit.search.graph.URIList
import com.keepit.common.db.Id
import com.keepit.model.User
import org.apache.lucene.util.BytesRef
import com.keepit.search.graph.Util

class UserGraphSearcher(searcher: Searcher) extends BaseGraphSearcher(searcher) {
  import UserGraphFields._

  override def getURIList(field: String, docid: Int): URIList = throw new UnsupportedOperationException
  override def getLongArray(field: String, docid: Int): Array[Long] = throw new UnsupportedOperationException

  def getFriends(userId: Id[User]): Set[Long] = {
    val docid = getDocId(userId.id)
    val docValues = reader.inner.getBinaryDocValues(friendsList)
    if (docValues == null){
      var ref = new BytesRef()
      docValues.get(docid, ref)
      if (ref.length > 0) Util.unpackLongArray(ref.bytes, ref.offset, ref.length).toSet else Set()
    } else Set()
  }
}

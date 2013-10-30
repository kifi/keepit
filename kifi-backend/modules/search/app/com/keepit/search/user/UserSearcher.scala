package com.keepit.search.user

import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.PriorityQueue
import com.keepit.search.index.Searcher
import com.keepit.social.BasicUser
import com.keepit.search.index.Indexer
import com.keepit.common.db.Id
import com.keepit.model.User
import play.api.libs.functional.syntax._
import play.api.libs.json._


class UserSearcher(searcher: Searcher) {
  def search(query: Query, maxHit: Int = 10): Array[UserHit] = {
    
    val pq = new PriorityQueue[UserHit](maxHit){
      override def lessThan(a: UserHit, b: UserHit): Boolean =  {
        val (ua, ub, ida, idb) = (a.basicUser, b.basicUser, a.id.id, b.id.id)
        (ua.firstName + ua.lastName < ub.firstName + ub.lastName ||
           (ua.firstName + ua.lastName == ub.firstName + ub.lastName && ida < idb))
      }
    }
    
    searcher.doSearch(query){ (scorer, reader) =>
      val bv = reader.getBinaryDocValues(UserIndexer.BASIC_USER_FIELD)
      val mapper = reader.getIdMapper
      var doc = scorer.nextDoc()
      while(doc != NO_MORE_DOCS){
        var ref = new BytesRef()
        bv.get(doc, ref)
        val user = BasicUser.fromByteArray(ref.bytes, ref.offset, ref.length)
        val id = Id[User](mapper.getId(doc))
        pq.insertWithOverflow(UserHit(id, user))
        doc = scorer.nextDoc()
      }
    }
    
    val N = pq.size
    var n = 0
    val users = new Array[UserHit](N)
    while(n < N){
      users(n) = pq.pop
      n += 1
    }
    users
  }

}
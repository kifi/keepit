package com.keepit.search.user

import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.PriorityQueue

import com.keepit.search.index.Searcher
import com.keepit.social.BasicUser



class UserSearcher(searcher: Searcher) {
  def search(query: Query, maxHit: Int = 10): Array[BasicUser] = {
    
    val pq = new PriorityQueue[BasicUser](maxHit){
      override def lessThan(a: BasicUser, b: BasicUser): Boolean =  a.firstName + a.lastName < b.firstName + b.lastName
    }
    
    searcher.doSearch(query){ (scorer, reader) =>
      val bv = reader.getBinaryDocValues(UserIndexer.BASIC_USER_FIELD)
      var doc = scorer.nextDoc()
      var overflow: BasicUser = null
      while(doc != NO_MORE_DOCS){
        var ref = new BytesRef()
        bv.get(doc, ref)
        val user = BasicUser.fromByteArray(ref.bytes, ref.offset, ref.length)
        pq.insertWithOverflow(user)
        doc = scorer.nextDoc()
      }
    }
    
    val N = pq.size
    var n = 0
    val users = new Array[BasicUser](N)
    while(n < N){
      users(n) = pq.pop
      n += 1
    }
    users
  }

}
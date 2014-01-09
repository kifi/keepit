package com.keepit.search.user

import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.PriorityQueue

import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.IdFilterCompressor
import com.keepit.search.Searcher
import com.keepit.social.BasicUser


class UserSearcher(searcher: Searcher) {

  def search(query: Query, maxHit: Int = 10, searchFilter: UserSearchFilter): UserSearchResult = {
    val idFilter = searchFilter.idFilter

    val pq = new PriorityQueue[UserHit](maxHit) {
      override def lessThan(a: UserHit, b: UserHit): Boolean = {
        val (ua, ub, ida, idb) = (a.basicUser, b.basicUser, a.id.id, b.id.id)
        (ua.firstName + ua.lastName > ub.firstName + ub.lastName ||
          (ua.firstName + ua.lastName == ub.firstName + ub.lastName && ida > idb))
      }
    }

    searcher.doSearch(query) { (scorer, reader) =>
      val bv = reader.getBinaryDocValues(UserIndexer.BASIC_USER_FIELD)
      val mapper = reader.getIdMapper
      var doc = scorer.nextDoc()
      while (doc != NO_MORE_DOCS) {
        val id = mapper.getId(doc)
        if (id >= 0 && searchFilter.accept(id)) {
          var ref = new BytesRef()
          bv.get(doc, ref)
          val user = BasicUser.fromByteArray(ref.bytes, ref.offset, ref.length)
          val userId = Id[User](id)
          val isFriend = searchFilter.getKifiFriends.contains(id)
          pq.insertWithOverflow(UserHit(userId, user, isFriend))
        }
        doc = scorer.nextDoc()
      }
    }

    val N = pq.size
    var n = N - 1
    val hits = new Array[UserHit](N)
    while (n >= 0) {
      hits(n) = pq.pop
      n -= 1
    }

    val ids = hits.foldLeft(idFilter) { (ids, h) => ids + h.id.id }
    val context = IdFilterCompressor.fromSetToBase64(ids)
    UserSearchResult(hits, context)
  }

  def searchPaging(query: Query, searchFilter: UserSearchFilter, pageNum: Int, pageSize: Int): UserSearchResult = {
    var hits = Vector.empty[UserHit]
    searcher.doSearch(query) { (scorer, reader) =>
      val bv = reader.getBinaryDocValues(UserIndexer.BASIC_USER_FIELD)
      val mapper = reader.getIdMapper
      var doc = scorer.nextDoc()
      while (doc != NO_MORE_DOCS) {
        val id = mapper.getId(doc)
        if (id >= 0 && searchFilter.accept(id)) {
          var ref = new BytesRef()
          bv.get(doc, ref)
          val user = BasicUser.fromByteArray(ref.bytes, ref.offset, ref.length)
          val userId = Id[User](id)
          val isFriend = searchFilter.getKifiFriends.contains(id)
          hits = hits :+ UserHit(userId, user, isFriend)
        }
        doc = scorer.nextDoc()
      }
    }
    val pageHits = hits.sortBy(x => x.basicUser.firstName + x.basicUser.lastName).drop(pageNum * pageSize).take(pageSize).toArray
    UserSearchResult(pageHits, "")
  }

}
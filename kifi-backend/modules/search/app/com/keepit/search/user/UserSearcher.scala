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
import com.keepit.typeahead.PrefixMatching
import com.keepit.typeahead.PrefixFilter


class UserSearcher(searcher: Searcher) {

  case class ScoredUserHit(hit: UserHit, score: Float) extends Ordered[ScoredUserHit] {
    // worse result < better result
    def compare(that: ScoredUserHit): Int = {
       if (this.score < that.score) return -1
       else if (this.score > that.score) return 1
       else {
         val (ua, ub, ida, idb) = (this.hit.basicUser, that.hit.basicUser, this.hit.id.id, that.hit.id.id)
         if (ua.firstName + ua.lastName > ub.firstName + ub.lastName ||
          (ua.firstName + ua.lastName == ub.firstName + ub.lastName && ida > idb)) -1     // prefer "smaller" name or smaller id
         else 1
       }
    }
  }

  private def nameMatchDist(user: BasicUser, queryTerms: Array[String]): Int = {
    val normalizedName = PrefixFilter.normalize(user.firstName + " " + user.lastName)
    PrefixMatching.distance(normalizedName, queryTerms)
  }

  def search(query: Query, maxHit: Int = 10, searchFilter: UserSearchFilter, queryTerms: Array[String] = Array()): UserSearchResult = {
    val idFilter = searchFilter.idFilter

    val pq = new PriorityQueue[ScoredUserHit](maxHit) {
      override def lessThan(a: ScoredUserHit, b: ScoredUserHit): Boolean = a < b
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
          val scoredHit = ScoredUserHit(UserHit(userId, user, isFriend), -1f * nameMatchDist(user, queryTerms))
          pq.insertWithOverflow(scoredHit)
        }
        doc = scorer.nextDoc()
      }
    }

    val N = pq.size
    var n = N - 1
    val hits = new Array[UserHit](N)
    while (n >= 0) {
      hits(n) = pq.pop.hit
      n -= 1
    }

    val ids = hits.foldLeft(idFilter) { (ids, h) => ids + h.id.id }
    val context = IdFilterCompressor.fromSetToBase64(ids)
    UserSearchResult(hits, context)
  }

  def searchPaging(query: Query, searchFilter: UserSearchFilter, pageNum: Int, pageSize: Int, queryTerms: Array[String] = Array()): UserSearchResult = {
    var hits = Vector.empty[ScoredUserHit]
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
          val score = -1f * nameMatchDist(user, queryTerms)
          hits = hits :+ ScoredUserHit(UserHit(userId, user, isFriend), score)
        }
        doc = scorer.nextDoc()
      }
    }
    val pageHits = hits.sortWith((a, b) => a > b).drop(pageNum * pageSize).take(pageSize).map{_.hit}.toArray
    UserSearchResult(pageHits, "")
  }

}
package com.keepit.search.user

import com.keepit.common.db.Id
import com.keepit.common.util.IdFilterCompressor
import com.keepit.model.User
import com.keepit.search.index.Searcher
import com.keepit.search.index.user._
import com.keepit.typeahead.{ PrefixFilter, PrefixMatching }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import org.apache.lucene.util.PriorityQueue

class DeprecatedUserSearcher(searcher: Searcher) {

  case class ScoredUserHit(hit: DeprecatedUserHit, score: Float) extends Ordered[ScoredUserHit] {
    // worse result < better result
    def compare(that: ScoredUserHit): Int = {
      if (this.score < that.score) return -1
      else if (this.score > that.score) return 1
      else {
        val (ua, ub, ida, idb) = (this.hit.basicUser, that.hit.basicUser, this.hit.id.id, that.hit.id.id)
        if (ua.firstName + ua.lastName > ub.firstName + ub.lastName ||
          (ua.firstName + ua.lastName == ub.firstName + ub.lastName && ida > idb)) -1 // prefer "smaller" name or smaller id
        else 1
      }
    }
  }

  class ScoredUserHitPQ(maxHit: Int) extends PriorityQueue[ScoredUserHit](maxHit) {
    override def lessThan(a: ScoredUserHit, b: ScoredUserHit): Boolean = a < b
  }

  private def nameMatchScoring(queryTerms: Array[String])(hit: DeprecatedUserHit): Float = {
    val user = hit.basicUser
    val normalizedName = PrefixFilter.normalize(user.firstName + " " + user.lastName)
    -1f * PrefixMatching.distance(normalizedName, queryTerms)
  }

  private def genMatchingFilter(queryTerms: Array[String]): Function1[DeprecatedUserHit, Boolean] = {
    if (queryTerms.forall(_.length() <= UserFields.PREFIX_MAX_LEN)) (u: DeprecatedUserHit) => true // prefix index guarantees correctness
    else {
      val longQueries = queryTerms.filter(_.length() > UserFields.PREFIX_MAX_LEN)
      (hit: DeprecatedUserHit) => longQueries.forall(query => query.contains("@") || hit.basicUser.firstName.toLowerCase.startsWith(query) || hit.basicUser.lastName.toLowerCase.startsWith(query)) // don't match email address with names. need test pass
    }
  }

  private def genHitsPriorityQueue(query: Query, searchFilter: DeprecatedUserSearchFilter, queueSize: Int)(scoreFunc: DeprecatedUserHit => Float)(additionalCheck: DeprecatedUserHit => Boolean): ScoredUserHitPQ = {
    val pq = new ScoredUserHitPQ(queueSize)

    searcher.search(query) { (scorer, reader) =>
      val bv = reader.getBinaryDocValues(UserFields.recordField)
      val mapper = reader.getIdMapper
      var doc = scorer.nextDoc()
      while (doc != NO_MORE_DOCS) {
        val id = mapper.getId(doc)
        if (id >= 0 && searchFilter.accept(id)) {
          val ref = bv.get(doc)
          val user = BasicUserSerializer.fromByteArray(ref.bytes, ref.offset, ref.length)
          val userId = Id[User](id)
          val isFriend = searchFilter.getKifiFriends.contains(id)
          val hit = DeprecatedUserHit(userId, user, isFriend)
          if (additionalCheck(hit)) {
            val scoredHit = ScoredUserHit(hit, scoreFunc(hit))
            pq.insertWithOverflow(scoredHit)
          }
        }
        doc = scorer.nextDoc()
      }
    }
    pq
  }

  // k least items, returned in desc order
  private def getKLeastSorted(pq: ScoredUserHitPQ, K: Int): Array[DeprecatedUserHit] = {
    val N = pq.size min K
    var n = N - 1
    val hits = new Array[DeprecatedUserHit](N)
    while (n >= 0) {
      hits(n) = pq.pop.hit
      n -= 1
    }
    hits
  }

  def search(query: Query, maxHit: Int = 10, searchFilter: DeprecatedUserSearchFilter, queryTerms: Array[String] = Array()): DeprecatedUserSearchResult = {
    val idFilter = searchFilter.idFilter
    val scoreFunc = nameMatchScoring(queryTerms)(_)
    val check = genMatchingFilter(queryTerms)
    val pq = genHitsPriorityQueue(query, searchFilter, maxHit)(scoreFunc)(check)
    val hits = getKLeastSorted(pq, maxHit)

    val ids = hits.foldLeft(idFilter) { (ids, h) => ids + h.id.id }
    val context = IdFilterCompressor.fromSetToBase64(ids)
    DeprecatedUserSearchResult(hits, context)
  }

  // pageNum starts from 0
  def searchPaging(query: Query, searchFilter: DeprecatedUserSearchFilter, pageNum: Int, pageSize: Int, queryTerms: Array[String] = Array()): DeprecatedUserSearchResult = {
    val scoreFunc = nameMatchScoring(queryTerms)(_)
    val check = genMatchingFilter(queryTerms)
    val pq = genHitsPriorityQueue(query, searchFilter, (pageNum + 1) * pageSize)(scoreFunc)(check)
    val pageHits = getKLeastSorted(pq, pageSize)
    DeprecatedUserSearchResult(pageHits, "")
  }
}

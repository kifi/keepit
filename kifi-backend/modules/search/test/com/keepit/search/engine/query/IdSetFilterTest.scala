package com.keepit.search.engine.query

import com.keepit.search.index.ArrayIdMapper
import com.keepit.search.util.LongArraySet
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.util.Bits
import org.specs2.mutable.Specification

class IdSetFilterTest extends Specification {

  "IdSetFilter" should {
    "correctly return docids for given ids" in {
      val allIds = Array[Long](90L, 80L, 70L, 60L, 50L, 40L, 30L, 20L, 10L)
      val myIds = Array[Long](10L, 30L, 50L, 70L, 90L, 100L, 200L, 300L)
      val mapper = new ArrayIdMapper(allIds)

      val filter = new IdSetFilter(LongArraySet.from(myIds))

      var iter = filter.getDocIdSet(mapper, new Bits.MatchAllBits(allIds.length)).iterator()
      var doc = iter.nextDoc()
      var filteredIds: Set[Long] = Set()
      var count: Int = 0
      while (doc < NO_MORE_DOCS) {
        filteredIds += allIds(doc)
        count += 1
        doc = iter.nextDoc()
      }
      val expected = Array[Long](10L, 30L, 50L, 70L, 90L)
      count === expected.length
      filteredIds === expected.toSet

      iter = filter.getDocIdSet(mapper, new Bits.MatchNoBits(allIds.length)).iterator()
      iter.nextDoc() === NO_MORE_DOCS
    }
  }
}

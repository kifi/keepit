package com.keepit.search.index

import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.SemanticVectorComposer
import com.keepit.search.SemanticVector
import com.keepit.search.BrowsingHistoryTracker
import com.keepit.search.ClickHistoryTracker
import com.keepit.search.MultiHashFilter
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.index.SegmentReader
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import scala.collection.mutable.ArrayBuffer

object PersonalizedSearcher {
  def apply(userId: Id[User], indexReader: WrappedIndexReader, ids: Set[Long],
            browsingHistoryTracker: BrowsingHistoryTracker,
            clickHistoryTracker: ClickHistoryTracker,
            svWeightMyBookMarks: Int,
            svWeightBrowsingHistory: Int,
            svWeightClickHistory: Int) = {
    new PersonalizedSearcher(indexReader, ids,
                             browsingHistoryTracker.getMultiHashFilter(userId),
                             clickHistoryTracker.getMultiHashFilter(userId),
                             svWeightMyBookMarks, svWeightBrowsingHistory, svWeightClickHistory)
  }

  def apply(searcher: Searcher, ids: Set[Long]) = {
    new PersonalizedSearcher(searcher.indexReader, ids, MultiHashFilter.emptyFilter, MultiHashFilter.emptyFilter, 1, 0, 0)
  }
}

class PersonalizedSearcher(override val indexReader: WrappedIndexReader, ids: Set[Long],
                           browsingFilter: MultiHashFilter, clickFilter: MultiHashFilter,
                           svWeightMyBookMarks: Int, svWeightBrowsingHistory: Int, svWeightClickHistory: Int)
extends Searcher(indexReader) {
  override protected def getSemanticVectorComposer(term: Term) = {
    val subReaders = indexReader.wrappedSubReaders
    val composer = new SemanticVectorComposer
    var vector = new SemanticVector(new Array[Byte](SemanticVector.arraySize))
    var i = 0
    while (i < subReaders.length) {
      val subReader = subReaders(i)
      val idMapper = subReader.getIdMapper
      val tp = subReader.termPositionsEnum(term)
      if (tp != null) {
        while (tp.nextDoc() < NO_MORE_DOCS) {
          val id = idMapper.getId(tp.docID())
          val weight = {
            if (clickFilter.mayContain(id)) svWeightClickHistory
            else if (browsingFilter.mayContain(id)) svWeightBrowsingHistory
            else if (ids.contains(id)) svWeightMyBookMarks
            else 0
          }
          if (weight > 0) {
            var freq = tp.freq()
            while (freq > 0) {
              freq -= 1
              tp.nextPosition()
              val payload = tp.getPayload()
              vector.set(payload.bytes, payload.offset, payload.length)
              composer.add(vector, weight)
            }
          }
        }
      }
      i += 1
    }
    composer
  }
}
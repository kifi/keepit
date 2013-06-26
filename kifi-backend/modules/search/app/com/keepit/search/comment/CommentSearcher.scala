package com.keepit.search.comment

import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.index.Searcher
import com.keepit.search.query.ConditionalQuery
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.util.PriorityQueue
import scala.collection.mutable.ArrayBuffer
import com.keepit.search.IdFilterCompressor

class CommentSearcher(searcher: Searcher) {
  import CommentFields._

  def search(userId: Id[User], query: Query, maxHits: Int, idFilter: Set[Long] = Set.empty[Long]): CommentSearchResult = {

    val pq = new PriorityQueue[CommentHit](maxHits){
      override def lessThan(a: CommentHit, b: CommentHit): Boolean = {
        (a.timestamp < b.timestamp || (a.timestamp == b.timestamp && a.id < b.id))
      }
    }

    val participantQuery = new TermQuery(new Term(participantIdField, userId.id.toString))
    val filterdQuery = new ConditionalQuery(query, participantQuery)
    var overflow: CommentHit = null

    searcher.doSearch(filterdQuery) { (scorer, reader) =>
      val mapper = reader.getIdMapper
      var doc = scorer.nextDoc()
      val timestampDocVals = reader.getNumericDocValues(timestampField)
      while (doc != NO_MORE_DOCS) {
        val id = mapper.getId(doc)
        if (id >= 0 && !idFilter.contains(id)) {
          val hit = if (overflow != null) {
            overflow.id = id
            overflow.timestamp = timestampDocVals.get(doc)
            overflow
          } else {
            new CommentHit(id, timestampDocVals.get(doc))
          }
          overflow = pq.insertWithOverflow(hit)
        }
       doc = scorer.nextDoc()
      }
    }

    var pqSize = pq.size
    val hitList = new Array[CommentHit](pqSize)
    while (pqSize > 0) {
      pqSize -= 1
      hitList(pqSize) = pq.pop()
    }

    val ids = hitList.foldLeft(idFilter){ (ids, h) => ids + h.id }
    val context = IdFilterCompressor.fromSetToBase64(ids)

    CommentSearchResult(hitList, context)
  }
}



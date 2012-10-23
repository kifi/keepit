package com.keepit.search.graph

import com.keepit.common.db.Id
import com.keepit.search.index.IdMapper
import com.keepit.search.index.Searcher
import org.apache.lucene.search.DocIdSet
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import scala.util.Sorting
import org.apache.lucene.index.Term

trait EdgeSet[S,D] {
  val sourceId: Id[S]

  def destIdSet: Set[Id[D]]
  
  def getDestDocIdSetIterator(searcher: Searcher): DocIdSetIterator
}

object EdgeSetUtil {
  implicit def toIterator(it: DocIdSetIterator) = new Iterator[Int] {
    var nextDocId = it.nextDoc()
    def hasNext = nextDocId < NO_MORE_DOCS
    def next = {
      var cur = nextDocId
      nextDocId = it.nextDoc()
      cur
    }
  }
}

class MaterializedEdgeSet[S,D](override val sourceId: Id[S], override val destIdSet: Set[Id[D]]) extends EdgeSet[S, D] {
  
  def getDestDocIdSetIterator(searcher: Searcher): DocIdSetIterator = getDestDocIdSetIterator(searcher.idMapper)
  
  private def getDestDocIdSetIterator(mapper: IdMapper): DocIdSetIterator = {
    val docids = destIdSet.flatMap{ id => mapper.getDocId(id.id) }.toArray
    Sorting.quickSort(docids)
    new DocIdSetIterator {
      var curDoc = NO_MORE_DOCS
      var curIdx = -1
      
      def docID() = curDoc
      
      def nextDoc() = {
        curIdx += 1
        curDoc = if(curIdx < docids.length) docids(curIdx) else NO_MORE_DOCS
        curDoc
      }
      
      def advance(target: Int) = {
        while (curDoc < target) nextDoc
        curDoc
      }
    }
  }
}


abstract class LuceneBackedEdgeSet[S, D](override val sourceId: Id[S], searcher: Searcher) extends EdgeSet[S, D] {
  import EdgeSetUtil._
  
  lazy val lazyDestIdSet = getDestDocIdSetIterator(searcher).map(docid => searcher.idMapper.getId(docid)).map(toId(_)).toSet

  override def destIdSet = lazyDestIdSet
  
  def getDestDocIdSetIterator(searcher: Searcher) = {
    val termDocs = searcher.indexReader.termDocs(createSourceTerm)
    
    new DocIdSetIterator {
      var curDoc = NO_MORE_DOCS
      
      def docID() = curDoc
      
      def nextDoc() = {
        curDoc = if (termDocs.next()) termDocs.doc() else {
          termDocs.close()
          NO_MORE_DOCS
        }
        curDoc
      }
      
      def advance(target: Int) = {
        curDoc = if (termDocs.skipTo(target)) termDocs.doc() else {
          termDocs.close()
          NO_MORE_DOCS
        }
        curDoc
      }
    }
  }
  
  def toId(longId: Long): Id[D]
  def createSourceTerm: Term
}

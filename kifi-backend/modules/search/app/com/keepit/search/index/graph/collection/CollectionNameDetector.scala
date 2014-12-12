package com.keepit.search.index.graph.collection

import org.apache.lucene.index.Term
import org.apache.lucene.index.DocsAndPositionsEnum
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.util.PriorityQueue
import scala.collection.JavaConversions._
import scala.math._
import org.apache.lucene.index.AtomicReader

class CollectionNameDetector(indexReader: AtomicReader, collectionIdList: Array[Long]) {

  def detectAll(terms: IndexedSeq[Term]): Set[(Int, Int, Long)] = {
    var result = Set.empty[(Int, Int, Long)] // (position, length, collectionId)
    detectPartialMatch(terms) { (position, length, collectionId) => result += ((position, length, collectionId)) }
    result
  }

  private def detectPartialMatch(terms: IndexedSeq[Term])(f: (Int, Int, Long) => Unit): Unit = {
    val numTerms = terms.size
    var index = 0
    while (index < numTerms) {
      val t = terms(index)
      val td = indexReader.termDocsEnum(t)
      if (td != null) {
        while (td.nextDoc() < NO_MORE_DOCS) f(index, 1, collectionIdList(td.docID))
      }
      index += 1
    }
  }
}


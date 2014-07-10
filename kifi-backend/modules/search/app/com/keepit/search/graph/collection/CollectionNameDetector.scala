package com.keepit.search.graph.collection

import org.apache.lucene.index.Term
import org.apache.lucene.index.DocsAndPositionsEnum
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.util.PriorityQueue
import scala.collection.JavaConversions._
import scala.math._
import org.apache.lucene.index.AtomicReader

class CollectionNameDetector(indexReader: AtomicReader, collectionIdList: Array[Long]) {

  def detectAll(terms: IndexedSeq[Term], partialMatch: Boolean): Set[(Int, Int, Long)] = {
    var result = Set.empty[(Int, Int, Long)] // (position, length, collectionId)
    if (partialMatch) {
      detectPartialMatch(terms) { (position, length, collectionId) => result += ((position, length, collectionId)) }
    } else {
      detectInternal(terms) { (position, length, collectionId) => result += ((position, length, collectionId)) }
    }
    result
  }

  private def detectPartialMatch(terms: IndexedSeq[Term])(f: (Int, Int, Long) => Unit): Unit = {
    val numTerms = terms.size
    var index = 0
    while (index < numTerms) {
      val t = terms(index)
      val tp = indexReader.termPositionsEnum(t)
      if (tp != null) {
        while (tp.nextDoc() < NO_MORE_DOCS) f(index, 1, collectionIdList(tp.docID))
      }
      index += 1
    }
  }

  private def detectInternal(terms: IndexedSeq[Term])(f: (Int, Int, Long) => Unit): Unit = {
    val numTerms = terms.size
    val pq = new PQ(terms.size)

    var index = 0
    var prevWord: Word = null
    while (index < numTerms) {
      val t = terms(index)
      val tp = indexReader.termPositionsEnum(t)
      if (tp == null) { // found a gap here
        findCollectionNames(pq, f) // pq will be cleared after execution
        prevWord = null
      } else {
        val freq = indexReader.docFreq(t)
        val w = new Word(index, freq, tp, prevWord)
        if (prevWord != null) prevWord.nextWord = w
        pq.insertWithOverflow(w)
        prevWord = w
      }
      index += 1
    }
    findCollectionNames(pq, f)
  }

  private def findCollectionNames(pq: PQ, onMatch: (Int, Int, Long) => Unit): Unit = {
    var count = pq.size

    if (count > 0) {
      var top = pq.top

      while (top.doc == -1) {
        if (top.nextDoc() == NO_MORE_DOCS) count -= 1
        top = pq.updateTop()
      }

      while (count > 0) {
        val doc = top.doc
        var word = findStart(top)
        if (word != null) {
          val phraseStart = word.index
          var wordOffset = 0
          while (word != null && word.doc == doc && word.checkPosition(phraseStart, wordOffset, doc, onMatch)) {
            wordOffset += 1
            word = word.nextWord
          }
        }
        while (top.doc == doc) {
          if (top.nextDoc() == NO_MORE_DOCS) count -= 1
          top = pq.updateTop()
        }
      }
    }
    pq.clear()
  }

  @inline private[this] def findStart(w: Word): Word = {
    val doc = w.doc
    var start = w
    while (start.prevWord != null && start.prevWord.doc == doc) {
      start = start.prevWord
    }
    start
  }

  private class Word(val index: Int, val freq: Int, tp: DocsAndPositionsEnum, val prevWord: Word) {

    var nextWord: Word = null

    var doc = -1

    def nextDoc() = {
      doc = tp.nextDoc()
      doc
    }

    def checkPosition(phraseStart: Int, wordOffset: Int, doc: Int, onMatch: (Int, Int, Long) => Unit): Boolean = {
      if (index == (phraseStart + wordOffset)) {
        var freq = tp.freq()
        while (freq > 0) {
          var data = tp.nextPosition()
          val pos = data >> 1
          if (pos > wordOffset) return false
          if (pos == wordOffset) {
            if ((data & 1) == 1) {
              onMatch(phraseStart, wordOffset + 1, collectionIdList(doc)) // one phrase matched
              return false // no need to continue
            }
            return true // position matched, continue if the next word is at the same doc
          }
          freq -= 1
        }
      }
      false // position didn't match, no need to continue
    }
  }

  private class PQ(sz: Int) extends PriorityQueue[Word](sz) {
    override def lessThan(a: Word, b: Word) = (a.doc < b.doc || (a.doc == b.doc && a.freq < b.freq)) // infrequent word first
  }
}


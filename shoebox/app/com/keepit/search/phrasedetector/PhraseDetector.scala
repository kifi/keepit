package com.keepit.search.phrasedetector

import com.keepit.common.db.{Id,SequenceNumber}
import com.keepit.common.logging.Logging
import com.keepit.model.Phrase
import com.keepit.search.index.Indexer
import com.keepit.search.index.Indexable
import com.keepit.search.Lang
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.index.DocsAndPositionsEnum
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.store.Directory
import org.apache.lucene.util.PriorityQueue
import com.google.inject.{Inject, Singleton}
import scala.slick.util.CloseableIterator
import scala.collection.JavaConversions._
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Await
import scala.concurrent.duration._

object PhraseDetector {
  val fieldName = "p"
  def createTerm(text: String) = new Term(fieldName, text)
}

@Singleton
class PhraseDetector @Inject() (indexer: PhraseIndexer) {
  def detectAll(terms: Array[Term]) = {
    var result = Set.empty[(Int, Int)] // (position, length)
    val pq = new PQ(terms.size)

    val pterms = terms.map{ term => PhraseDetector.createTerm(term.text()) }.zipWithIndex
    indexer.getSearcher.indexReader.leaves.foreach{ subReaderContext =>
      pterms.foreach{ case (pterm, index) =>
        val tp = subReaderContext.reader.termPositionsEnum(pterm)
        if (tp != null && tp.nextDoc() < NO_MORE_DOCS) pq.insertWithOverflow(new Word(index, tp))
      }
      var top = pq.top
      while (pq.size > 1) {
        val doc = top.doc
        val phraseIndex = top.index
        var phraseLength = 0
        while (top != null && top.doc == doc && top.index == (phraseIndex + phraseLength) && top.hasPosition(phraseLength)) {
          phraseLength += 1
          if (top.isEndOfPhrase) {
            result += ((phraseIndex, phraseLength)) // one phrase found
          }
          top = if (top.nextDoc() < NO_MORE_DOCS) {
            pq.updateTop()
          } else {
            pq.pop()
            pq.top
          }
        }
        while (top != null && top.doc == doc) {
          top = if (top.nextDoc() < NO_MORE_DOCS) {
            pq.updateTop()
          } else {
            pq.pop()
            pq.top
          }
        }
      }
      pq.clear()
    }
    result
  }

  private class Word(val index: Int, tp: DocsAndPositionsEnum) {
    var doc = tp.docID()
    private[this] var end = 0

    def nextDoc() = {
      doc = tp.nextDoc()
      doc
    }

    def hasPosition(target: Int): Boolean = {
      var freq = tp.freq()
      while (freq > 0) {
        var pos = tp.nextPosition()
        end = pos & 1
        pos = pos >> 1
        if (pos >= target) return (pos == target)
        freq -= 1
      }
      false
    }

    def isEndOfPhrase = (end == 1)
  }

  private class PQ(sz: Int) extends PriorityQueue[Word](sz) {
    override def lessThan(a: Word, b: Word) = (a.doc < b.doc || (a.doc == b.doc && a.index < b.index))
  }
}

abstract class PhraseIndexer(indexDirectory: Directory, indexWriterConfig: IndexWriterConfig) extends Indexer[Phrase](indexDirectory, indexWriterConfig) {
  def reload(): Unit
  def reload(indexableIterator: Iterator[PhraseIndexable], refresh: Boolean = true): Unit
}

class PhraseIndexerImpl(indexDirectory: Directory, indexWriterConfig: IndexWriterConfig, shoeboxClient: ShoeboxServiceClient) extends PhraseIndexer(indexDirectory, indexWriterConfig) with Logging  {

  final val BATCH_SIZE = 200000

  def reload() {
      log.info("[PhraseIndexer] reloading phrase index")
      val indexableIterator = new Iterator[PhraseIndexable] {
        var cache = collection.mutable.Queue[PhraseIndexable]()
        var page = 0
        private def update() = {
          if(cache.size <= 1) {
              cache = cache ++ Await.result(shoeboxClient.getPhrasesByPage(page, BATCH_SIZE), 5 seconds).map(p => new PhraseIndexable(p.id.get, p.phrase, p.lang))
              page += 1
          }
        }

        def next() = {
          update()
          cache.dequeue
        }
        def hasNext() = {
          update()
          cache.size > 0
        }
      }
      //val indexableIterator = phraseRepo.allIterator.map(p => new PhraseIndexable(p.id.get, p.phrase, p.lang))
      log.info("[PhraseIndexer] Iterator created")
      reload(indexableIterator, refresh = false)
      log.info("[PhraseIndexer] refreshing searcher")
      refreshSearcher()
      log.info("[PhraseIndexer] phrase import complete")
  }

  def reloadWithCloseableIterator(indexableIterator: CloseableIterator[PhraseIndexable], refresh: Boolean) {
    try { reload(indexableIterator, refresh) }
    finally { indexableIterator.close }
  }

  def reload(indexableIterator: Iterator[PhraseIndexable], refresh: Boolean = true) {
    deleteAllDocuments(refresh = false)
    indexDocuments(indexableIterator, BATCH_SIZE, refresh = false){ s => log.info(s"[PhraseIndexer] imported ${s.length} phrases. First in batch id: ${s.headOption.map(t=> t._1.id.id).getOrElse("")}") }
    if (refresh) refreshSearcher()
  }
}


class PhraseIndexable(override val id: Id[Phrase], phrase: String, lang: Lang) extends Indexable[Phrase] with PhraseFieldBuilder {
  override val sequenceNumber = SequenceNumber.ZERO
  override val isDeleted = false
  override def buildDocument = {
    val doc = super.buildDocument
    doc.add(buildPhraseField("p", phrase, lang))
    doc
  }
}

object RemoveOverlapping {
  def removeInclusions(phrases: Set[(Int, Int)]) = {
    val sortedIntervals = phrases.map{x => ((x._1 ,x._1 + x._2 - 1))}.toArray.sortWith((a,b) => (a._1 < b._1) || (a._1 == b._1 && a._2 > b._2))  // for same position, longer one comes first
    var minStartPos = -1
    var minEndPos = -1
    val intervals = for( i <- 0 until sortedIntervals.size
      if( sortedIntervals(i)._1 >= minStartPos && sortedIntervals(i)._2 > minEndPos ) ) yield {
        minStartPos = sortedIntervals(i)._1 + 1
        minEndPos = sortedIntervals(i)._2
        sortedIntervals(i)
    }
    intervals.map{x => (x._1, x._2 - x._1 + 1)}.toSet
  }
}

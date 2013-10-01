package com.keepit.search.phrasedetector

import com.keepit.common.db.{Id,SequenceNumber}
import com.keepit.common.healthcheck.Healthcheck.INTERNAL
import com.keepit.common.healthcheck.{HealthcheckError, HealthcheckPlugin}
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
import scala.collection.mutable.{ListBuffer}

object PhraseDetector {
  val fieldName = "p"
  def createTerm(text: String) = new Term(fieldName, text)
}

@Singleton
class PhraseDetector @Inject() (indexer: PhraseIndexer) {

  def detect(terms: IndexedSeq[Term]) = {
    RemoveOverlapping.removeInclusions(detectAll(terms))
  }

  def detectAll(terms: IndexedSeq[Term]): Set[(Int,Int)] = {
    var result = Set.empty[(Int, Int)] // (position, length)
    detectInternal(terms){ (position, length) => result += ((position, length)) }
    result
  }

  private def detectInternal(terms: IndexedSeq[Term])(f: (Int, Int)=>Unit): Unit = {
    val pq = new PQ(terms.size)

    val pterms = terms.map{ term => PhraseDetector.createTerm(term.text()) }
    indexer.getSearcher.indexReader.leaves.foreach{ subReaderContext =>
      val numTerms = pterms.size
      var index = 0
      while (index < numTerms) {
        val tp = subReaderContext.reader.termPositionsEnum(pterms(index))
        if (tp == null) { // found a gap here
          findPhrases(pq, f)// pq will be cleared after execution
        } else {
          pq.insertWithOverflow(new Word(index, tp))
        }
        index += 1
      }
      findPhrases(pq, f)
    }
  }

  private def findPhrases(pq: PQ, f: (Int, Int)=>Unit): Unit = {
    var effectiveCount = pq.size - 1

    if (effectiveCount > 0) {
      var top = pq.top

      while (top.doc == -1) {
        if (top.nextDoc() == NO_MORE_DOCS) effectiveCount -= 1
        top = pq.updateTop()
      }

      while (effectiveCount > 0) {
        val doc = top.doc
        val phraseIndex = top.index
        var phraseLength = 0
        while (top.doc == doc && top.index == (phraseIndex + phraseLength) && top.hasPosition(phraseLength)) {
          phraseLength += 1
          if (top.isEndOfPhrase) {
            f(phraseIndex, phraseLength) // one phrase found
          }
          if (top.nextDoc() == NO_MORE_DOCS) effectiveCount -= 1
          top = pq.updateTop()
        }
        while (top.doc == doc) {
          if (top.nextDoc() == NO_MORE_DOCS) effectiveCount -= 1
          top = pq.updateTop()
        }
      }
    }
    pq.clear()
  }

  private class Word(val index: Int, tp: DocsAndPositionsEnum) {

    var doc = -1

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

class PhraseIndexerImpl(
  indexDirectory: Directory,
  indexWriterConfig: IndexWriterConfig,
  healthcheckPlugin: HealthcheckPlugin,
  shoeboxClient: ShoeboxServiceClient) extends PhraseIndexer(indexDirectory, indexWriterConfig) with Logging  {

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
    indexDocuments(indexableIterator, BATCH_SIZE, refresh = false)
    if (refresh) refreshSearcher()
  }

  override def onFailure(indexable: Indexable[Phrase], e: Throwable): Unit = {
    val msg = s"failed to build document for id=${indexable.id}: ${e.toString}"
    healthcheckPlugin.addError(HealthcheckError(errorMessage = Some(msg), callType = INTERNAL))
    super.onFailure(indexable, e)
  }

  override def onSuccess(indexable: Indexable[Phrase]): Unit = {
    if (firstInBatch < 0) firstInBatch = indexable.id.id
    countInBatch += 1
    super.onSuccess(indexable)
  }

  private[this] var firstInBatch = -1L
  private[this] var countInBatch = 0

  override def onStart(batch: Seq[Indexable[Phrase]]): Unit = {
    firstInBatch = -1L
    countInBatch = 0
  }

  override def onCommit(successful: Seq[Indexable[Phrase]]): Unit = {
    log.info(s"[PhraseIndexer] imported ${countInBatch} phrases. First in batch id: ${firstInBatch}")
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
    val sortedIntervals = phrases.toArray.sortWith((a,b) => (a._1 < b._1) || (a._1 == b._1 && a._2 > b._2))  // for same position, longer one comes first
    var minStartPos = -1
    var minEndPos = -1
    val intervals = for( i <- 0 until sortedIntervals.size
      if( sortedIntervals(i)._1 >= minStartPos && sortedIntervals(i)._1 + sortedIntervals(i)._2 > minEndPos ) ) yield {
        minStartPos = sortedIntervals(i)._1 + 1
        minEndPos = sortedIntervals(i)._1 + sortedIntervals(i)._2
        sortedIntervals(i)
    }
    intervals.toSet
  }

  def weakRemoveInclusions(phrases: Set[(Int, Int)]) = {
    // finest in the sense of max number of sub-intervals. Not unique.
    def getFinestDecomposition(decomp: Set[ListBuffer[(Int, Int)]]) = {
      decomp.foldLeft(ListBuffer.empty[(Int, Int)])((finest, buf) => if (buf.size > finest.size) buf else finest)
    }

    def getIntervalMap(phrases: Set[(Int, Int)]) = {
      phrases.groupBy(_._1).foldLeft(Map.empty[Int, Set[Int]])((m, x) => m + (x._1 -> x._2.map(_._2)))
    }

    val intervalMaps = getIntervalMap(phrases)
    val newIntervals = removeInclusions(phrases)
    val rv = ListBuffer.empty[(Int, Int)]
    for(interval <- newIntervals){
      val decomp = decompose(interval, intervalMaps)
      val finest = getFinestDecomposition(decomp.getOrElse(Set(ListBuffer.empty[(Int, Int)])))
      rv.appendAll(finest)
    }
    rv.toSet
  }

  /**
   * x: (startpos, len)
   * intervals: key = start position. Set[Int] = lengths
   *
   */
  def decompose(x: (Int, Int), intervals: Map[Int, Set[Int]]): Option[Set[ListBuffer[(Int, Int)]]] = {
    if ( x._2 == 0 ) return Some(Set(ListBuffer.empty[(Int, Int)]))
    if ( x._2 < 0 ) return None

    var solu = Set.empty[ListBuffer[(Int, Int)]]
    var found = false
    intervals.get(x._1) match {
      case None => None
      case Some(lens) => {
        for(len <- lens){
          val subSolu = decompose((x._1 + len, x._2 - len), intervals)
          subSolu match {
            case None => None
            case Some(subsolu) => {found = true; solu ++= subsolu.foldLeft(Set.empty[ListBuffer[(Int, Int)]]){(s, list) => list.prepend((x._1, len)); s + list}}
          }
        }
        if (found) Some(solu) else None
      }
    }
  }
}

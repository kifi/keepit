package com.keepit.search.phrasedetector

import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.DocUtil
import com.keepit.search.index.Indexer
import com.keepit.search.index.Indexable
import com.keepit.search.index.FieldDecoder
import com.keepit.search.Lang
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.index.TermPositions
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.store.Directory
import org.apache.lucene.util.PriorityQueue
import org.apache.lucene.util.Version
import java.io.File
import java.io.FileReader
import java.io.LineNumberReader
import java.io.IOException
import scala.collection.mutable.ArrayBuffer
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import org.apache.lucene.store.RAMDirectory
import com.keepit.common.db.slick.Database
import com.keepit.model.{PhraseRepo, Phrase}
import scala.slick.util.CloseableIterator
import play.api.Play.current
import com.keepit.common.logging.Logging

object PhraseDetector {
  val termTemplate = new Term("p", "")
  def createTerm(text: String) = termTemplate.createTerm(text)
}

@Singleton
class PhraseDetector @Inject() (indexer: PhraseIndexer) {
  def detectAll(terms: Array[Term]) = {
    var result = Set.empty[(Int, Int)] // (position, length)
    val pq = new PQ(terms.size)

    val pterms = terms.map{ term => PhraseDetector.createTerm(term.text()) }.zipWithIndex
    indexer.getSearcher.indexReader.getSequentialSubReaders.foreach{ reader =>
      pterms.foreach{ case (pterm, index) =>
        val tp = reader.termPositions(pterm)
        if (tp.next()) pq.insertWithOverflow(new Word(index, tp))
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
          top = if (top.next()) {
            pq.updateTop()
          } else {
            pq.pop()
            pq.top
          }
        }
        while (top != null && top.doc == doc) {
          top = if (top.next()) {
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

  private class Word(val index: Int, tp: TermPositions) {
    var doc = tp.doc()
    private[this] var end = 0

    def next() = {
      if (tp.next()) {
        doc = tp.doc()
        true
      } else {
        doc = NO_MORE_DOCS
        false
      }
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

  private class PQ(sz: Int) extends PriorityQueue[Word] {
    super.initialize(sz)
    override def lessThan(a: Word, b: Word) = (a.doc < b.doc || (a.doc == b.doc && a.index < b.index))
  }
}


object PhraseIndexer {
  def apply(db: Database, phraseRepo: PhraseRepo): PhraseIndexer = apply(new RAMDirectory, db, phraseRepo)

  def apply(indexDirectory: Directory, db: Database, phraseRepo: PhraseRepo): PhraseIndexer  = {
    val analyzer = DefaultAnalyzer.forIndexing
    val config = new IndexWriterConfig(Version.LUCENE_36, analyzer)
    new PhraseIndexerImpl(indexDirectory, db, phraseRepo, config)
  }
}

abstract class PhraseIndexer(indexDirectory: Directory, indexWriterConfig: IndexWriterConfig) extends Indexer[Phrase](indexDirectory, indexWriterConfig) {
  def reload(): Unit
  def reload(indexableIterator: Iterator[PhraseIndexable], refresh: Boolean = true): Unit
}

class PhraseIndexerImpl(indexDirectory: Directory, db: Database, phraseRepo: PhraseRepo, indexWriterConfig: IndexWriterConfig) extends PhraseIndexer(indexDirectory, indexWriterConfig) with Logging  {

  def reload() {
    db.readOnly { implicit session =>
      log.info("[PhraseIndexer] reloading phrase index")
      val indexableIterator = phraseRepo.allIterator.map(p => new PhraseIndexable(p.id.get, p.phrase, p.lang))
      reloadWithCloseableIterator(indexableIterator, refresh = false)
      log.info("[PhraseIndexer] refreshing searcher")
      refreshSearcher()
      log.info("[PhraseIndexer] phrase import complete")
    }
  }

  def reloadWithCloseableIterator(indexableIterator: CloseableIterator[PhraseIndexable], refresh: Boolean) {
    try { reload(indexableIterator, refresh) }
    finally { indexableIterator.close }
  }

  def reload(indexableIterator: Iterator[PhraseIndexable], refresh: Boolean = true) {
    deleteAllDocuments(refresh = false)
    indexDocuments(indexableIterator, 500000, refresh = false){ s => log.info(s"[PhraseIndexer] imported ${s.length} phrases. First in batch id: ${s.headOption.map(t=> t._1.id.id).getOrElse("")}") }
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

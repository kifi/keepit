package com.keepit.search.phrasedetector

import com.keepit.common.db.Id
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
      while (pq.size > 1) {
        var top = pq.top
        val doc = top.doc
        val phraseIndex = top.index
        var position = 0
        while (top.doc == doc) {
          if (top.index == (phraseIndex + position) && top.hasPosition(position)) {
            position += 1
            if (top.isEndOfPhrase) {
              result += ((phraseIndex, position)) // one phrase found
            }
            top = if (top.next()) {
              pq.updateTop()
            } else {
              if (pq.size > 1) pq.pop()
              pq.top
            }
          } else {
            while (top.doc == doc) {
              top = if (top.next()) {
                pq.updateTop()
              } else {
                if (pq.size > 1) pq.pop()
                pq.top
              }
            }
          }
        }
      }
      pq.clear()
    }
    result
  }

  private class Word(val index: Int, tp: TermPositions) {
    var doc = tp.doc()
    private[this] var pos = 0
    private[this] var end = 0
    private[this] var freq = tp.freq()

    def next() = {
      if (tp.next()) {
        freq = tp.freq()
        doc = tp.doc()
        true
      } else {
        freq = 0
        doc = NO_MORE_DOCS
        false
      }
    }

    def hasPosition(target: Int): Boolean = {
      while (freq > 0) {
        pos = tp.nextPosition()
        end = pos & 1
        pos = pos >> 1
        if (pos == target) return true
        if (pos > target) return false
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

class Phrase

object PhraseIndexer {
  def apply(): PhraseIndexer = apply(new RAMDirectory, None)

  def apply(indexDirectory: Directory, dataDirectory: Option[File] = None): PhraseIndexer  = {
    val analyzer = DefaultAnalyzer.forIndexing
    val config = new IndexWriterConfig(Version.LUCENE_36, analyzer)
    new PhraseIndexer(indexDirectory, dataDirectory, config)
  }
}

class PhraseIndexer(indexDirectory: Directory, dataDirectory: Option[File], indexWriterConfig: IndexWriterConfig) extends Indexer[Phrase](indexDirectory, indexWriterConfig)  {

  def reload(): Unit = dataDirectory.foreach{ dir =>
    var id = -1
    if (dir.exists) {
      deleteAllDocuments()
      log.info("loading phrases from: %s".format(dir.toString))
      val indexableItertor = dir.listFiles.iterator.flatMap{ file =>
      val lang = Lang(file.getName)
      val reader = new LineNumberReader(new FileReader(file))
      new Iterator[PhraseIndexable] {
        var line = reader.readLine
        def hasNext() = (line != null)
        def next() = {
          val cur = line
            line = reader.readLine()
            id += 1
            new PhraseIndexable(Id[Phrase](id), cur, lang)
          }
        }
      }
      reload(indexableItertor)
      log.info("finished loading from: %s".format(dir.toString))
    } else {
      throw new IOException("no such directory: %s".format(dir.toString))
    }
  }

  def reload(indexableItertor: Iterator[PhraseIndexable]): Unit = {
    deleteAllDocuments()
    indexDocuments(indexableItertor, 100000){ s => /* nothing */ }
  }

  def buildIndexable(data: Phrase): Indexable[Phrase] = throw new UnsupportedOperationException
  def buildIndexable(id: Id[Phrase]): Indexable[Phrase] = throw new UnsupportedOperationException
}

class PhraseIndexable(override val id: Id[Phrase], phrase: String, lang: Lang) extends Indexable[Phrase] with PhraseFieldBuilder {
  override def buildDocument = {
    val doc = super.buildDocument
    doc.add(buildPhraseField("p", phrase, lang))
    doc
  }
}

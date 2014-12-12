package com.keepit.search.engine.query

import com.keepit.search.SearchConfig
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.util.LocalAlignment
import com.keepit.search.util.LocalAlignment._
import org.apache.lucene.document.{ Document, Field, TextField }
import org.apache.lucene.index.{ DirectoryReader, IndexWriter, IndexWriterConfig, SlowCompositeReaderWrapper, Term }
import org.apache.lucene.search.{ DocIdSetIterator, IndexSearcher }
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.util.Version
import org.specs2.mutable._

import scala.collection.mutable.ArrayBuffer

class ProximityQueryTest extends Specification {

  val config = new IndexWriterConfig(Version.LATEST, DefaultAnalyzer.defaultAnalyzer)

  val gapPenalty = SearchConfig.defaultConfig.asFloat("proximityGapPenalty")

  val ramDir = new RAMDirectory
  val indexReader = {
    val writer = new IndexWriter(ramDir, config)
    (0 until 10).foreach { d =>
      val text = ("abc %s def %s ghi".format("xyz " * d, "xyz " * (10 - d)))
      val doc = new Document()
      doc.add(new Field("B", text, TextField.TYPE_NOT_STORED))
      writer.addDocument(doc)
    }
    (10 until 20).foreach { d =>
      val text = ("aaa bbb %s ccc ddd".format("xyz " * (d - 10)))
      val doc = new Document()
      doc.add(new Field("B", text, TextField.TYPE_NOT_STORED))
      writer.addDocument(doc)
    }
    (20 until 30).foreach { d =>
      val text = ("eee fff %s ggg hhh".format("xyz " * (30 - d)))
      val doc = new Document()
      doc.add(new Field("B", text, TextField.TYPE_NOT_STORED))
      writer.addDocument(doc)
    }

    writer.commit()
    writer.close()

    DirectoryReader.open(ramDir)
  }

  val reader = SlowCompositeReaderWrapper.wrap(indexReader)
  val readerContextLeaves = reader.leaves()
  val readerContext = readerContextLeaves.get(0)

  val searcher = new IndexSearcher(reader)

  private def mkProxTerms(terms: Term*): Seq[Seq[Term]] = {
    terms.map { Seq(_) }
  }

  "ProximityQuery" should {

    "score using proximity (two terms)" in {
      readerContextLeaves.size === 1

      var q = ProximityQuery(mkProxTerms(new Term("B", "abc"), new Term("B", "def")), gapPenalty = gapPenalty, powerFactor = 1f)
      var weight = searcher.createNormalizedWeight(q)

      var scorer = weight.scorer(readerContext, reader.getLiveDocs)
      val buf = new ArrayBuffer[(Int, Float)]()
      var doc = scorer.nextDoc()
      while (doc < DocIdSetIterator.NO_MORE_DOCS) {
        buf += ((doc, scorer.score()))
        doc = scorer.nextDoc()
      }
      indexReader.numDocs() === 30
      buf.size === 10
      buf.sortBy(_._2).map(_._1) === Seq(9, 8, 7, 6, 5, 4, 3, 2, 1, 0)

      q = ProximityQuery(mkProxTerms(new Term("B", "def"), new Term("B", "ghi")), gapPenalty = gapPenalty, powerFactor = 1f)
      weight = searcher.createNormalizedWeight(q)

      (weight != null) === true

      scorer = weight.scorer(readerContext, reader.getLiveDocs)
      buf.clear
      doc = scorer.nextDoc()
      while (doc < DocIdSetIterator.NO_MORE_DOCS) {
        buf += ((doc, scorer.score()))
        doc = scorer.nextDoc()
      }
      buf.size === 10
      buf.sortBy(_._2).map(_._1) === Seq(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    }

    "score using proximity (two phrases)" in {
      var q = ProximityQuery(mkProxTerms(new Term("B", "aaa"), new Term("B", "bbb"), new Term("B", "ccc"), new Term("B", "ddd")), gapPenalty = gapPenalty, powerFactor = 1f)
      var weight = searcher.createNormalizedWeight(q)

      var scorer = weight.scorer(readerContext, reader.getLiveDocs)
      val buf = new ArrayBuffer[(Int, Float)]()
      var doc = scorer.nextDoc()
      while (doc < DocIdSetIterator.NO_MORE_DOCS) {
        buf += ((doc, scorer.score()))
        doc = scorer.nextDoc()
      }
      indexReader.numDocs() === 30
      buf.size === 10
      buf.sortBy(_._2).map(_._1) === Seq(19, 18, 17, 16, 15, 14, 13, 12, 11, 10)

      q = ProximityQuery(mkProxTerms(new Term("B", "eee"), new Term("B", "fff"), new Term("B", "ggg"), new Term("B", "hhh")), gapPenalty = gapPenalty, powerFactor = 1f)
      weight = searcher.createNormalizedWeight(q)

      (weight != null) === true

      scorer = weight.scorer(readerContext, reader.getLiveDocs)
      buf.clear
      doc = scorer.nextDoc()
      while (doc < DocIdSetIterator.NO_MORE_DOCS) {
        buf += ((doc, scorer.score()))
        doc = scorer.nextDoc()
      }
      buf.size === 10
      buf.sortBy(_._2).map(_._1) === Seq(20, 21, 22, 23, 24, 25, 26, 27, 28, 29)
    }

    "score using proximity (four terms)" in {
      var q = ProximityQuery(mkProxTerms(new Term("B", "aaa"), new Term("B", "bbb"), new Term("B", "ccc"), new Term("B", "ddd")), gapPenalty = gapPenalty, powerFactor = 1f)
      var weight = searcher.createNormalizedWeight(q)

      var scorer = weight.scorer(readerContext, reader.getLiveDocs)
      var buf = new ArrayBuffer[(Int, Float)]()
      var doc = scorer.nextDoc()
      while (doc < DocIdSetIterator.NO_MORE_DOCS) {
        buf += ((doc, scorer.score()))
        doc = scorer.nextDoc()
      }
      indexReader.numDocs() === 30
      buf.size === 10
      buf.sortBy(_._2).map(_._1) === Seq(19, 18, 17, 16, 15, 14, 13, 12, 11, 10)

      q = ProximityQuery(mkProxTerms(new Term("B", "aaa"), new Term("B", "ccc"), new Term("B", "bbb"), new Term("B", "ddd")), gapPenalty = gapPenalty, powerFactor = 1f)
      weight = searcher.createNormalizedWeight(q)

      scorer = weight.scorer(readerContext, reader.getLiveDocs)
      buf = new ArrayBuffer[(Int, Float)]()
      doc = scorer.nextDoc()
      while (doc < DocIdSetIterator.NO_MORE_DOCS) {
        buf += ((doc, scorer.score()))
        doc = scorer.nextDoc()
      }
      indexReader.numDocs() === 30
      buf.size === 10
      buf.sortBy(_._2).map(_._1) === Seq(19, 18, 17, 16, 15, 14, 13, 12, 11, 10)
    }

    "score using proximity with repeating terms" in {
      readerContextLeaves.size === 1

      var q = ProximityQuery(mkProxTerms(new Term("B", "abc"), new Term("B", "abc"), new Term("B", "def")), gapPenalty = gapPenalty, powerFactor = 1f)
      var weight = searcher.createNormalizedWeight(q)

      var scorer = weight.scorer(readerContext, reader.getLiveDocs)
      val buf = new ArrayBuffer[(Int, Float)]()
      var doc = scorer.nextDoc()
      while (doc < DocIdSetIterator.NO_MORE_DOCS) {
        buf += ((doc, scorer.score()))
        doc = scorer.nextDoc()
      }
      indexReader.numDocs() === 30
      buf.size === 10
      buf.sortBy(_._2).map(_._1) === Seq(9, 8, 7, 6, 5, 4, 3, 2, 1, 0)

      q = ProximityQuery(mkProxTerms(new Term("B", "def"), new Term("B", "def"), new Term("B", "ghi"), new Term("B", "ghi")), gapPenalty = gapPenalty, powerFactor = 1f)
      weight = searcher.createNormalizedWeight(q)

      (weight != null) === true

      scorer = weight.scorer(readerContext, reader.getLiveDocs)
      buf.clear
      doc = scorer.nextDoc()
      while (doc < DocIdSetIterator.NO_MORE_DOCS) {
        buf += ((doc, scorer.score()))
        doc = scorer.nextDoc()
      }
      buf.size === 10
      buf.sortBy(_._2).map(_._1) === Seq(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    }

    "score using proximity (equiv terms)" in {
      readerContextLeaves.size === 1

      var q = ProximityQuery(Seq(Seq(new Term("B", "abc"), new Term("B", "aaa")), Seq(new Term("B", "def"))), gapPenalty = gapPenalty, powerFactor = 1f)
      var weight = searcher.createNormalizedWeight(q)

      var scorer = weight.scorer(readerContext, reader.getLiveDocs)
      val buf = new ArrayBuffer[(Int, Float)]()
      var doc = scorer.nextDoc()
      while (doc < DocIdSetIterator.NO_MORE_DOCS) {
        buf += ((doc, scorer.score()))
        doc = scorer.nextDoc()
      }
      indexReader.numDocs() === 30
      buf.size === 20
      buf.sortBy(h => (h._2, h._1)).map(_._1) === Seq(10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)

      q = ProximityQuery(Seq(Seq(new Term("B", "def"), new Term("B", "aaa")), Seq(new Term("B", "ccc"), new Term("B", "ghi"))), gapPenalty = gapPenalty, powerFactor = 1f)
      weight = searcher.createNormalizedWeight(q)

      (weight != null) === true

      scorer = weight.scorer(readerContext, reader.getLiveDocs)
      buf.clear
      doc = scorer.nextDoc()
      while (doc < DocIdSetIterator.NO_MORE_DOCS) {
        buf += ((doc, scorer.score()))
        doc = scorer.nextDoc()
      }
      buf.size === 20
      buf.sortBy(_._2).map(_._1) === Seq(0, 19, 1, 18, 2, 17, 3, 16, 4, 15, 5, 14, 6, 13, 7, 12, 8, 11, 9, 10)
    }

    "not return hits when no term" in {
      val q = ProximityQuery(mkProxTerms(), gapPenalty = gapPenalty, powerFactor = 1f)
      val weight = searcher.createNormalizedWeight(q)
      (weight != null) === true

      val scorer = weight.scorer(readerContext, reader.getLiveDocs)
      scorer === null
    }
    "make a phrase dictionary correctly" in {
      def termIdSeq(ids: Int*) = ids.map(LocalAlignment.intToTermId(_))

      val termIds = termIdSeq(0, 1, 2, 3, 4, 5, 6, 1, 2).toArray
      val phrases1 = Set((1, 3), (5, 2))

      ProximityQuery.buildPhraseDict(termIds, phrases1).toSet ===
        Set((termIdSeq(0), TermMatch(0)), (termIdSeq(1), TermMatch(7)), (termIdSeq(2), TermMatch(8)), (termIdSeq(4), TermMatch(4)), (termIdSeq(1, 2, 3), PhraseMatch(1, 3)), (termIdSeq(5, 6), PhraseMatch(5, 2)))

      val phrases2 = Set((1, 3), (5, 2), (6, 2))
      ProximityQuery.buildPhraseDict(termIds, phrases2).toSet ===
        Set((termIdSeq(0), TermMatch(0)), (termIdSeq(2), TermMatch(8)), (termIdSeq(4), TermMatch(4)), (termIdSeq(1, 2, 3), PhraseMatch(1, 3)), (termIdSeq(5, 6), PhraseMatch(5, 2)), (termIdSeq(6, 1), PhraseMatch(6, 2)))

      val phrases3 = Set((1, 3), (5, 2), (0, 1), (5, 1))
      ProximityQuery.buildPhraseDict(termIds, phrases3).toSet ===
        Set((termIdSeq(0), TermMatch(0)), (termIdSeq(1), TermMatch(7)), (termIdSeq(2), TermMatch(8)), (termIdSeq(4), TermMatch(4)), (termIdSeq(1, 2, 3), PhraseMatch(1, 3)), (termIdSeq(5, 6), PhraseMatch(5, 2)), (termIdSeq(5), TermMatch(5)))

      val phrases4 = Set((0, 3), (3, 3), (6, 3))
      ProximityQuery.buildPhraseDict(termIds, phrases4).toSet ===
        Set((termIdSeq(0, 1, 2), PhraseMatch(0, 3)), (termIdSeq(3, 4, 5), PhraseMatch(3, 3)), (termIdSeq(6, 1, 2), PhraseMatch(6, 3)))
    }
  }
}

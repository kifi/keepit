package com.keepit.search.query

import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import scala.math._
import scala.collection.mutable.ArrayBuffer
import org.apache.lucene.document.Document
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.index.TermEnum
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.DefaultSimilarity
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.util.Version
import com.keepit.search.index.DefaultAnalyzer
import org.apache.lucene.document.Field
import org.apache.lucene.search.DocIdSetIterator
import com.keepit.search.index.Searcher

class ProximityQueryTest extends Specification {

  val indexingAnalyzer = DefaultAnalyzer.forIndexing
  val config = new IndexWriterConfig(Version.LUCENE_36, indexingAnalyzer)

  val ramDir = new RAMDirectory
  val indexReader = {
    val writer = new IndexWriter(ramDir, config)
    (0 until 10).foreach{ d =>
      val text = ("abc %s def %s ghi".format("xyz "*d, "xyz "*(10 - d)))
      val doc = new Document()
      doc.add(new Field("B", text, Field.Store.NO, Field.Index.ANALYZED, Field.TermVector.NO))
      writer.addDocument(doc)
    }
    (10 until 20).foreach{ d =>
      val text = ("aaa bbb %s ccc ddd".format("xyz "*(d - 10)))
      val doc = new Document()
      doc.add(new Field("B", text, Field.Store.NO, Field.Index.ANALYZED, Field.TermVector.NO))
      writer.addDocument(doc)
    }
    (20 until 30).foreach{ d =>
      val text = ("eee fff %s ggg hhh".format("xyz "*(30 - d)))
      val doc = new Document()
      doc.add(new Field("B", text, Field.Store.NO, Field.Index.ANALYZED, Field.TermVector.NO))
      writer.addDocument(doc)
    }

    writer.commit()
    writer.close()

    IndexReader.open(ramDir)
  }

  val searcher = Searcher(indexReader)

  "ProximityQuery" should {

    "score using proximity (two terms)" in {
      var q = ProximityQuery(Seq(new Term("B", "abc"), new Term("B", "def")))
      var weight = searcher.createNormalizedWeight(q)

      var scorer = weight.scorer(indexReader, true, true)
      val buf = new ArrayBuffer[(Int, Float)]()
      var doc = scorer.nextDoc()
      while (doc < DocIdSetIterator.NO_MORE_DOCS) {
        buf += ((doc, scorer.score()))
        doc = scorer.nextDoc()
      }
      indexReader.numDocs() === 30
      buf.size === 10
      buf.sortBy(_._2).map(_._1) === Seq(9, 8, 7, 6, 5, 4, 3, 2, 1, 0)

      q = ProximityQuery(Seq(new Term("B", "def"), new Term("B", "ghi")))
      weight = searcher.createNormalizedWeight(q)

      (weight != null) === true

      scorer = weight.scorer(indexReader, true, true)
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
      var q = ProximityQuery(Seq(new Term("B", "aaa"), new Term("B", "bbb"), new Term("B", "ccc"), new Term("B", "ddd")))
      var weight = searcher.createNormalizedWeight(q)

      var scorer = weight.scorer(indexReader, true, true)
      val buf = new ArrayBuffer[(Int, Float)]()
      var doc = scorer.nextDoc()
      while (doc < DocIdSetIterator.NO_MORE_DOCS) {
        buf += ((doc, scorer.score()))
        doc = scorer.nextDoc()
      }
      indexReader.numDocs() === 30
      buf.size === 10
      buf.sortBy(_._2).map(_._1) === Seq(19, 18, 17, 16, 15, 14, 13, 12, 11, 10)

      q = ProximityQuery(Seq(new Term("B", "eee"), new Term("B", "fff"), new Term("B", "ggg"), new Term("B", "hhh")))
      weight = searcher.createNormalizedWeight(q)

      (weight != null) === true

      scorer = weight.scorer(indexReader, true, true)
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
      var q = ProximityQuery(Seq(new Term("B", "aaa"), new Term("B", "bbb"), new Term("B", "ccc"), new Term("B", "ddd")))
      var weight = searcher.createNormalizedWeight(q)

      var scorer = weight.scorer(indexReader, true, true)
      var buf = new ArrayBuffer[(Int, Float)]()
      var doc = scorer.nextDoc()
      while (doc < DocIdSetIterator.NO_MORE_DOCS) {
        buf += ((doc, scorer.score()))
        doc = scorer.nextDoc()
      }
      indexReader.numDocs() === 30
      buf.size === 10
      buf.sortBy(_._2).map(_._1) === Seq(19, 18, 17, 16, 15, 14, 13, 12, 11, 10)

      q = ProximityQuery(Seq(new Term("B", "aaa"), new Term("B", "ccc"), new Term("B", "bbb"), new Term("B", "ddd")))
      weight = searcher.createNormalizedWeight(q)

      scorer = weight.scorer(indexReader, true, true)
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

    "not return hits when no term" in {
      val q = ProximityQuery(Seq.empty[Term])
      val weight = searcher.createNormalizedWeight(q)
      (weight != null) === true

      val scorer = weight.scorer(indexReader, true, true)
      scorer === null
    }
  }
}

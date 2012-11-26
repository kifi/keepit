package com.keepit.search.query

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
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
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.util.Version
import com.keepit.search.index.DefaultAnalyzer
import org.apache.lucene.document.Field
import org.apache.lucene.search.DocIdSetIterator

@RunWith(classOf[JUnitRunner])
class ProximityQueryTest extends SpecificationWithJUnit {

  val indexingAnalyzer = DefaultAnalyzer.forIndexing
  val config = new IndexWriterConfig(Version.LUCENE_36, indexingAnalyzer)

  val ramDir = new RAMDirectory
  val indexReader = {
    val writer = new IndexWriter(ramDir, config)
    (0 until 10).foreach{ d =>
      val text = ("abc %s def %s ghi".format("xyz "*d, "xyz "*(5 - d)))
      val doc = new Document()
      doc.add(new Field("B", text, Field.Store.NO, Field.Index.ANALYZED, Field.TermVector.NO))
      writer.addDocument(doc)
    }
    writer.commit()
    writer.close()

    IndexReader.open(ramDir)
  }

  val searcher = new IndexSearcher(indexReader)

  "ProximityQuery" should {

    "score using proximity" in {
      var q = ProximityQuery(Set(new Term("B", "abc"), new Term("B", "def")))
      var weight = searcher.createNormalizedWeight(q)
      (weight != null) === true

      var scorer = weight.scorer(indexReader, true, true)
      val buf = new ArrayBuffer[(Int, Float)]()
      var doc = scorer.nextDoc()
      while (doc < DocIdSetIterator.NO_MORE_DOCS) {
        buf += ((doc, scorer.score()))
        doc = scorer.nextDoc()
      }
      indexReader.numDocs() === 10
      buf.size === 10
      buf.sortBy(_._2).map(_._1) === Seq(9, 8, 7, 6, 5, 4, 3, 2, 1, 0)

      q = ProximityQuery(Set(new Term("B", "def"), new Term("B", "ghi")))
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

    "not return hits when the number of terms is 1 or less" in {
      var q = ProximityQuery(Set(new Term("B", "abc")))
      var weight = searcher.createNormalizedWeight(q)
      (weight != null) === true

      var scorer = weight.scorer(indexReader, true, true)
      scorer.nextDoc() === DocIdSetIterator.NO_MORE_DOCS

      q = ProximityQuery(Set.empty[Term])
      weight = searcher.createNormalizedWeight(q)
      (weight != null) === true

      scorer = weight.scorer(indexReader, true, true)
      scorer.nextDoc() === DocIdSetIterator.NO_MORE_DOCS
    }
  }
}

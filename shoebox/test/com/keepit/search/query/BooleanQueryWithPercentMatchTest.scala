package com.keepit.search.query

import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import scala.math._
import scala.collection.mutable.ArrayBuffer
import com.keepit.search.index.DefaultAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.TextField
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.SlowCompositeReaderWrapper
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.similarities.DefaultSimilarity
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.util.Version
import org.apache.lucene.search.DocIdSetIterator

class BooleanQueryWithPercentMatchTest extends Specification {

  val indexingAnalyzer = DefaultAnalyzer.forIndexing
  val config = new IndexWriterConfig(Version.LUCENE_41, indexingAnalyzer)
  val similarity = new DefaultSimilarity()

  val ramDir = new RAMDirectory
  val indexReader = {
    val writer = new IndexWriter(ramDir, config)
    (0 until 10).foreach{ d =>
      val text = ("%s %s %s".format("aaa "* (d % 5), "bbb "*(d % 3) , "ccc "*(d % 2)))
      val doc = new Document()
      doc.add(new Field("B", text, TextField.TYPE_NOT_STORED))
      writer.addDocument(doc)
    }
    writer.commit()
    writer.close()

    IndexReader.open(ramDir)
  }

  val reader = new SlowCompositeReaderWrapper(indexReader)
  val readerContextLeaves = reader.leaves()
  val readerContext = readerContextLeaves.get(0)

  val searcher = new IndexSearcher(reader)
  searcher.setSimilarity(new DefaultSimilarity {
    override def queryNorm(sumOfSquaredWeights: Float) = 1.0f
  })


  val aaa = new Term("B", "aaa")
  val bbb = new Term("B", "bbb")
  val ccc = new Term("B", "ccc")
  val aaaIdf = similarity.idf(indexReader.docFreq(aaa), indexReader.numDocs())
  val bbbIdf = similarity.idf(indexReader.docFreq(bbb), indexReader.numDocs())
  val cccIdf = similarity.idf(indexReader.docFreq(ccc), indexReader.numDocs())

  println("%f %f %f".format(aaaIdf, bbbIdf, cccIdf))

  def doQuery(query: Query) = {
    var weight = searcher.createNormalizedWeight(query)
    (weight != null) === true

    var scorer = weight.scorer(readerContext, true, true, reader.getLiveDocs)
    val buf = new ArrayBuffer[(Int, Float)]()
    var doc = scorer.nextDoc()
    while (doc < DocIdSetIterator.NO_MORE_DOCS) {
      buf += ((doc, scorer.score()))
      doc = scorer.nextDoc()
    }
    buf
  }

  "BooleanQueryWithPercentMatch" should {

    "filter result according to importance of terms (two term cases)" in {
      indexReader.numDocs() === 10

      var q = new BooleanQueryWithPercentMatch(false)
      q.add(new TermQuery(aaa), Occur.SHOULD)
      q.add(new TermQuery(bbb), Occur.SHOULD)
      doQuery(q).map(_._1) === Seq(1, 2, 3, 4, 5, 6, 7, 8, 9)

      q setPercentMatch(50.0f)
      doQuery(q).map(_._1) === Seq(1, 2, 4, 5, 7, 8) // docs with bbb

      q = new BooleanQueryWithPercentMatch(false)
      q.add(new TermQuery(bbb), Occur.SHOULD)
      q.add(new TermQuery(ccc), Occur.SHOULD)
      doQuery(q).map(_._1) === Seq(1, 2, 3, 4, 5, 7, 8, 9)

      q.setPercentMatch(50.0f)
      doQuery(q).map(_._1) === Seq(1, 3, 5, 7, 9) // docs with ccc
    }

    "filter result according to importance of terms (three term cases)" in {
      indexReader.numDocs() === 10


      var q = new BooleanQueryWithPercentMatch(false)
      q.add(new TermQuery(aaa), Occur.SHOULD)
      q.add(new TermQuery(bbb), Occur.SHOULD)
      q.add(new TermQuery(ccc), Occur.SHOULD)
      doQuery(q).map(_._1) === Seq(1, 2, 3, 4, 5, 6, 7, 8, 9)

      var pct = (bbbIdf + cccIdf) / (aaaIdf + bbbIdf + cccIdf) * 100.0f
      q.setPercentMatch(pct)
      doQuery(q).map(_._1) === Seq(1, 5, 7) // docs with bbb and ccc

      pct = (aaaIdf * 0.1f + bbbIdf + cccIdf) / (aaaIdf + bbbIdf + cccIdf) * 100.0f
      q.setPercentMatch(pct)
      doQuery(q).map(_._1) === Seq(1, 5, 7) // docs with bbb and ccc
    }

    "search with required terms" in {
      indexReader.numDocs() === 10

      var q = new BooleanQueryWithPercentMatch(false)
      q.add(new TermQuery(aaa), Occur.MUST)
      q.add(new TermQuery(bbb), Occur.MUST)
      doQuery(q).map(_._1) === Seq(1, 2, 4, 7, 8)

      q setPercentMatch(50.0f)
      doQuery(q).map(_._1) === Seq(1, 2, 4, 7, 8)

      q = new BooleanQueryWithPercentMatch(false)
      q.add(new TermQuery(bbb), Occur.MUST)
      q.add(new TermQuery(ccc), Occur.MUST)
      doQuery(q).map(_._1) === Seq(1, 5, 7)

      q.setPercentMatch(50.0f)
      doQuery(q).map(_._1) === Seq(1, 5, 7)

      q = new BooleanQueryWithPercentMatch(false)
      q.add(new TermQuery(aaa), Occur.MUST)
      q.add(new TermQuery(bbb), Occur.MUST)
      q.add(new TermQuery(ccc), Occur.MUST)
      doQuery(q).map(_._1) === Seq(1, 7)

      q.setPercentMatch(50.0f)
      doQuery(q).map(_._1) === Seq(1, 7)
    }
  }
}

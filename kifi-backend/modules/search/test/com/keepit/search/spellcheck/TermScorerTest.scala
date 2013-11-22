package com.keepit.search.spellcheck

import scala.math.log

import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.FieldType
import org.apache.lucene.document.TextField
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import org.specs2.mutable.Specification

import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.VolatileIndexDirectoryImpl
import scala.math.abs

class TermScorerTest extends Specification {

  val articles = Seq("abc abc abc def", "abc def", "abc abd deg xyz")
  val analyzer = DefaultAnalyzer.forIndexing
  val EPSILON = 1e-5f

  def equals(a: Float, b: Float) = abs(a - b) < EPSILON

  def mkDoc(content: String) = {
    val doc = new Document()
    val ts = analyzer.createLazyTokenStream("c", content)
    doc.add(new Field("c", ts, new FieldType(TextField.TYPE_NOT_STORED)))
    doc
  }

  "TermScorer" should {
    "work" in {
      val articleIndexDir = new VolatileIndexDirectoryImpl()
      val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)

      val indexWriter = new IndexWriter(articleIndexDir, config)
      articles.foreach{ x => indexWriter.addDocument(mkDoc(x)) }
      indexWriter.close()

      def log2(x: Double) = log(x)/log(2)

      val statsReader = new TermStatsReaderImpl(articleIndexDir, "c")
      val scorer = new TermScorer(statsReader, false)
      equals(scorer.scoreSingleTerm("abc"), log2(1 + 3f).toFloat) === true         // 3 intersections
      equals(scorer.scorePairTerms("def", "deg"), scorer.minPairTermsScore) === true          // zero intersection, smoothed to min score
    }

    "adjScore and orderdAdj should work" in {
      def log2(x: Double) = log(x)/log(2)

      val articleIndexDir = new VolatileIndexDirectoryImpl()
      val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)
      val indexWriter = new IndexWriter(articleIndexDir, config)
      val texts = Seq("ab x1 x2 x3 x4 cd", "ab x1 x2 x3 x4 x5 x6 cd", "ab ab y1 y2 ab ab")
      texts.foreach{ x => indexWriter.addDocument(mkDoc(x)) }
      indexWriter.close()

      val statsReader = new TermStatsReaderImpl(articleIndexDir, "c")
      var scorer = new TermScorer(statsReader, true, false)
      var score = scorer.scorePairTerms("ab", "cd")
      var numInter = 2
      var minDist = 4
      (score - log2(1 + numInter.toDouble) * scorer.gaussianScore(minDist - 1)).max(EPSILON) === EPSILON
      score = scorer.scorePairTerms("cd", "y1")
      equals(score, scorer.minPairTermsScore) === true       // zero intersection. smoothed to min score

      scorer = new TermScorer(statsReader, true, true)
      score = scorer.scorePairTerms("cd", "ab")
      equals(score, log2(1 + numInter).toFloat*scorer.minPairTermsScore) === true

    }
  }
}
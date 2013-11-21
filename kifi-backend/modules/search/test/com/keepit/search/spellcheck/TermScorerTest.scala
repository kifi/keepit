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

class TermScorerTest extends Specification {

  val articles = Seq("abc abc abc def", "abc def", "abc abd deg xyz")
  val analyzer = DefaultAnalyzer.forIndexing

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
      (scorer.scoreSingleTerm("abc") - log2(1 + 3f)).max(1e-5) === 1e-5          // 3 intersections
      (scorer.scorePairTerms("def", "deg") - scorer.minPairTermsScore).max(1e-5f) === 1e-5f          // zero intersection, smoothed to min score
    }

    "adjScore should work" in {
      val articleIndexDir = new VolatileIndexDirectoryImpl()
      val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)
      val indexWriter = new IndexWriter(articleIndexDir, config)
      val texts = Seq("ab x1 x2 x3 x4 cd", "ab x1 x2 x3 x4 x5 x6 cd", "ab ab y1 y2 ab ab")
      texts.foreach{ x => indexWriter.addDocument(mkDoc(x)) }
      indexWriter.close()

      val statsReader = new TermStatsReaderImpl(articleIndexDir, "c")
      val scorer = new TermScorer(statsReader, true)
      var score = scorer.scorePairTerms("ab", "cd")
      var numInter = 2
      var minDist = 4
      (score - numInter * scorer.gaussianScore(minDist)).max(1e-5) === 1e-5
      score = scorer.scorePairTerms("cd", "y1")
      (score - scorer.minPairTermsScore).max(1e-5f) === 1e-5f       // zero intersection. smoothed to min score
    }
  }
}
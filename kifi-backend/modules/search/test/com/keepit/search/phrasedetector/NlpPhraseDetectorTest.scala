package com.keepit.search.phrasedetector

import org.specs2.mutable.Specification
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.util.Version
import com.keepit.search.Lang

class NlpPhraseDetectorTest extends Specification {

  private val en = Lang("en")
  private val de = Lang("de")

  "NlpPhraseDetector" should {
    "detect phrases and correctly mapping them to Lucene tokenStream offsets" in {
      val analyzer = new StandardAnalyzer(Version.LUCENE_47)
      var queryText = "this is a critical test that should not fail"
      NlpPhraseDetector.detectAll(queryText, analyzer, en) === Set((0, 2), (2, 2)) // (critical test, should fail), Lucene tokenstream = (critical test should fail)

      queryText = "text mining and machine learning"
      NlpPhraseDetector.detectAll(queryText, analyzer, en) === Set((0, 2), (2, 2))

      queryText = "install photoshop on windows 7"
      NlpPhraseDetector.detectAll(queryText, analyzer, en) === Set((0, 3)) // tokenStream = (install photoshop windows 7)
    }

    // need to revisit this later. Need to be more tolerant.
    // Primary reason is that Stanford Parser may introduce new tokens for/at special characters
    "not try to detect if query contains non alphanumeric" in {
      val analyzer = new StandardAnalyzer(Version.LUCENE_47)
      val queryText = "dummy_query"
      NlpPhraseDetector.detectAll(queryText, analyzer, en) === Set.empty[(Int, Int)]
    }

    "not try to detect if query language is not English" in {
      val analyzer = new StandardAnalyzer(Version.LUCENE_47)
      val queryText = "il me dit que je suis belle"
      NlpPhraseDetector.detectAll(queryText, analyzer, de) === Set.empty[(Int, Int)]
    }

    "not try to detect single string" in {
      val analyzer = new StandardAnalyzer(Version.LUCENE_47)
      val queryText = "foo"
      NlpPhraseDetector.detectAll(queryText, analyzer, en) === Set.empty[(Int, Int)]
    }
  }

}

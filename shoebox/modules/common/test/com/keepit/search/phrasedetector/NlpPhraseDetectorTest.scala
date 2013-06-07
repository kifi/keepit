package com.keepit.search.phrasedetector

import org.specs2.mutable.Specification
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.util.Version

class NlpPhraseDetectorTest extends Specification {

  "NlpPhraseDetector" should {
    "detect phrases and correctly mapping them to Lucene tokenStream offsets" in {
      val analyzer = new StandardAnalyzer(Version.LUCENE_42)
      val queryText = "this is a critical test that should not fail"
      NlpPhraseDetector.detectAll(queryText, analyzer) === Set((0, 2), (2, 2))    // (critical test, should fail), Lucene tokenstream = (critical test should fial)
    }

    // need to revisit this later. Need to be more tolerant.
    // Primary reason is that Stanford Parser may introduce new tokens for/at special characters
    "not try to detect if query contains non alphanumeric" in {
       val analyzer = new StandardAnalyzer(Version.LUCENE_42)
       val queryText = "dummy_query"
       NlpPhraseDetector.detectAll(queryText, analyzer) === Set.empty[(Int, Int)]
    }

    "not try to detect if query language is not English" in {
      val analyzer = new StandardAnalyzer(Version.LUCENE_42)
      val queryText = "il me dit que je suis belle"
      NlpPhraseDetector.detectAll(queryText, analyzer) === Set.empty[(Int, Int)]
    }
  }

}
package com.keepit.search.phrasedetector

import com.keepit.common.db.Id
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.search.Lang
import com.keepit.search.index.DefaultAnalyzer
import org.apache.lucene.index.{IndexWriterConfig, Term}
import org.apache.lucene.store.RAMDirectory
import org.specs2.mutable._
import play.api.Play.current
import play.api.test.Helpers._
import com.keepit.model.Phrase
import com.keepit.test.DeprecatedEmptyApplication
import com.keepit.inject._
import java.io.StringReader
import com.keepit.shoebox.ShoeboxServiceClient
import org.apache.lucene.util.Version
import scala.collection.mutable.ListBuffer

class PhraseDetectorTest extends Specification with ApplicationInjector {

  "PhraseDetectorTest" should {
    "detects phrases in input text" in {
        running(new DeprecatedEmptyApplication()) {
        val indexer = new PhraseIndexerImpl(new RAMDirectory(), new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), inject[HealthcheckPlugin], inject[ShoeboxServiceClient])
        val lang = Lang("en")
        val phrases = List(
          "classroom project",
          "pilot project",
          "product research",
          "operations research",
          "test pilot",
          "research project",
          "human genome",
          "human genome project",
          "genome research project")
        val testcases = List(
          ("human genome", Set((0,2))),
          ("human genome research", Set((0,2))),
          ("human genome research project", Set((0,2),(1,3))),
          ("human genome project", Set((0,3))),
          ("product research project", Set((0,2),(1,2))),
          ("large classroom project", Set((1,2))))

        indexer.reload(phrases.zipWithIndex.map{ case (p, i) => new PhraseIndexable(Id[Phrase](i), p, lang) }.iterator)

        val detector = new PhraseDetector(indexer)
        val analyzer = DefaultAnalyzer.forIndexingWithStemmer(Lang("en"))

        def toTerms(text: String) = {
          indexer.getFieldDecoder("b").decodeTokenStream(analyzer.tokenStream("b", new StringReader(text))).map{ case (t, _, _) => new Term("b", t) }.toArray
        }

        val ok = testcases.forall{ case (text, expected) =>
          var input = toTerms(text)
          val output = detector.detectAll(input)
          output === expected
          true
        }
        ok === true
      }
    }
    
    
    "removal inclusion phrases" in {
      var phrases = Set((0, 2), (1, 1), (2, 3), (2, 4), (3,2), (4,3))   // (position, len)
      RemoveOverlapping.removeInclusions(phrases) === Set((0,2), (2, 4), (4, 3))
      
      phrases = Set.empty[(Int, Int)]
      RemoveOverlapping.removeInclusions(phrases) === phrases
    }
    
    "interval decomposer decompses correctly" in {
      var phrases = Set((0, 4), (0, 1), (0,2), (1, 3), (2, 1), (2,2), (2, 3))
      var intervals = Map( 0 -> Set(1, 2, 4), 1 -> Set(3), 2 -> Set(1, 2, 3))
      RemoveOverlapping.decompose((0,4), intervals) === Some(Set( ListBuffer((0, 4)), ListBuffer((0, 2), (2, 2)), ListBuffer((0, 1), (1, 3)) ))
      
      RemoveOverlapping.decompose((0,6), intervals) === None
    }
    
    "correctly and weakly remove overlapping" in {
      var phrases = Set((0, 4), (0, 1), (0,2), (1, 3), (2, 1), (2,2), (2, 4))
      RemoveOverlapping.weakRemoveInclusions(phrases) === Set((0, 2), (2, 2), (2, 4))
      
      phrases = Set((0, 10), (0, 6), (6, 4), (0, 2), (2, 4), (6, 1), (7, 3), (1, 8), (2, 2), (4, 2), (6, 3))
      RemoveOverlapping.weakRemoveInclusions(phrases) === Set((0, 2), (2, 2), (4, 2), (6, 1), (7, 3))
      
      phrases = Set((0,3), (0, 1), (1, 2))
      RemoveOverlapping.weakRemoveInclusions(phrases) === Set((0, 1), (1, 2))
      
      phrases = Set((0,3))
      RemoveOverlapping.weakRemoveInclusions(phrases) === Set((0, 3))
      
      phrases = Set.empty[(Int, Int)]
      RemoveOverlapping.removeInclusions(phrases) === phrases
    }
  }
}

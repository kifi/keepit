package com.keepit.search.index.phrase

import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.search.Lang
import com.keepit.search.index.{ Indexable, VolatileIndexDirectory, DefaultAnalyzer }
import org.apache.lucene.index.Term
import org.specs2.mutable._
import com.keepit.model.Phrase
import com.keepit.inject._
import java.io.StringReader
import com.keepit.shoebox.{ FakeShoeboxServiceModule, ShoeboxServiceClient }
import scala.collection.mutable.ListBuffer
import com.keepit.common.db.SequenceNumber
import com.keepit.test.CommonTestInjector

class PhraseDetectorTest extends Specification with CommonTestInjector {

  "PhraseDetectorTest" should {
    "detects all phrases in input text" in {
      withInjector(FakeShoeboxServiceModule()) { implicit injector =>
        val indexer = new PhraseIndexerImpl(new VolatileIndexDirectory(), inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
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
          ("human genome", Set((0, 2))),
          ("human genome research", Set((0, 2))),
          ("human genome research project", Set((0, 2), (1, 3), (2, 2))),
          ("human genome project", Set((0, 2), (0, 3))),
          ("product research project", Set((0, 2), (1, 2))),
          ("large classroom project", Set((1, 2))))

        indexer.indexDocuments(phrases.zipWithIndex.map { case (p, i) => new PhraseIndexable(Id[Phrase](i), SequenceNumber(i), false, p, lang) }.iterator, 10)

        val detector = new PhraseDetector(indexer)
        val analyzer = DefaultAnalyzer.getAnalyzerWithStemmer(Lang("en"))

        def toTerms(text: String) = {
          Indexable.getFieldDecoder(PhraseFields.decoders)("b").decodeTokenStream(analyzer.tokenStream("b", new StringReader(text))).map { case (t, _, _) => new Term("b", t) }.toArray
        }

        val ok = testcases.forall {
          case (text, expected) =>
            val input = toTerms(text)
            val output = detector.detectAll(input)
            output === expected
            true
        }
        ok === true
      }
    }

    "removal inclusion phrases" in {
      var phrases = Set((0, 2), (1, 1), (2, 3), (2, 4), (3, 2), (4, 3)) // (position, len)
      RemoveOverlapping.removeInclusions(phrases) === Set((0, 2), (2, 4), (4, 3))

      phrases = Set.empty[(Int, Int)]
      RemoveOverlapping.removeInclusions(phrases) === phrases
    }

    "interval decomposer decompses correctly" in {
      var phrases = Set((0, 4), (0, 1), (0, 2), (1, 3), (2, 1), (2, 2), (2, 3))
      var intervals = Map(0 -> Set(1, 2, 4), 1 -> Set(3), 2 -> Set(1, 2, 3))
      RemoveOverlapping.decompose((0, 4), intervals) === Some(Set(ListBuffer((0, 4)), ListBuffer((0, 2), (2, 2)), ListBuffer((0, 1), (1, 3))))

      RemoveOverlapping.decompose((0, 6), intervals) === None
    }

    "correctly and weakly remove overlapping" in {
      var phrases = Set((0, 4), (0, 1), (0, 2), (1, 3), (2, 1), (2, 2), (2, 4))
      RemoveOverlapping.weakRemoveInclusions(phrases) === Set((0, 2), (2, 2), (2, 4))

      phrases = Set((0, 10), (0, 6), (6, 4), (0, 2), (2, 4), (6, 1), (7, 3), (1, 8), (2, 2), (4, 2), (6, 3))
      RemoveOverlapping.weakRemoveInclusions(phrases) === Set((0, 2), (2, 2), (4, 2), (6, 1), (7, 3))

      phrases = Set((0, 3), (0, 1), (1, 2))
      RemoveOverlapping.weakRemoveInclusions(phrases) === Set((0, 1), (1, 2))

      phrases = Set((0, 3))
      RemoveOverlapping.weakRemoveInclusions(phrases) === Set((0, 3))

      phrases = Set.empty[(Int, Int)]
      RemoveOverlapping.removeInclusions(phrases) === phrases
    }
  }
}

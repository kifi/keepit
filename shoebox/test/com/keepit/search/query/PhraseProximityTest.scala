package com.keepit.search.query

import org.specs2.mutable.Specification
import com.keepit.search.phrasedetector.PhraseHelper
import com.keepit.search.phrasedetector.PhraseProximityQuery
import org.apache.lucene.analysis.core.WhitespaceAnalyzer
import org.apache.lucene.util.Version
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.SlowCompositeReaderWrapper
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.index.Term
import scala.collection.mutable.ArrayBuffer
import org.apache.lucene.search.DocIdSetIterator

class PhraseProximityTest extends Specification{
  val terms = Array("machine", "learning", "and", "machine", "translation")
  val phrases = Set((0,2), (3,2))
  val phraseHelper = new PhraseHelper(terms, phrases)
  val gapPenalty = PhraseProximityQuery.gapPenalty

  "PhraseHelper" should {
    "correctly construct phraseMap" in {
      phraseHelper.phraseMap === Map("machine learning" -> (0, 2), "and" -> (1, 1), "machine translation"-> (2, 2))			// (id, len)
    }

    "correctly detect phrase locations" in {
      var pos = Array(5, 6, 7, 8, 9)
      var tokens = Array("machine", "learning", "and", "machine", "translation")
      phraseHelper.getMatchedPhrases(pos, tokens) === Array((0, 2, 6), (1, 1, 7), (2, 2, 9))								// (id, len, endingPos)

      pos = Array(4, 6, 7, 8, 9)
      phraseHelper.getMatchedPhrases(pos, tokens) === Array((1, 1, 7), (2, 2, 9))

      pos = Array(4, 5, 6, 7)
      tokens = Array("learning", "and", "machine", "translation")
      phraseHelper.getMatchedPhrases(pos, tokens) === Array((1, 1, 5), (2, 2, 7))

      pos = Array(1, 2, 3, 4)
      tokens = Array("machine", "machine", "translation", "learning")
      phraseHelper.getMatchedPhrases(pos, tokens) === Array((2, 2, 3))
    }

    "correctly compute scores" in {

      var matches = Array((0, 2, 1), (1, 1, 2), (2, 2, 4))												// (machine learning)(and)(machine translation), 3 consecutive phrases
      var score = phraseHelper.getMatchedScore(matches)
      var correct = 5*(5+1)/2.0f - 5*(5-1)*gapPenalty/2.0f												// should match max possible score
      assert(math.abs(score - correct) < 1e-4 )

      matches = Array((1,1,2), (0, 2, 5), (0, 2, 12), (1, 1, 13), (2, 2, 15))							// add some confusion terms: (and)...(machine translation)......(machine learning)(and)(machine translation)
      score = phraseHelper.getMatchedScore(matches)
      assert(math.abs(score - correct) < 1e-4 )															// should still match max possible score

      matches = Array.empty[(Int,Int,Int)]
      score = phraseHelper.getMatchedScore(matches)
      assert(score == 0)
    }
  }


  val analyzer = new WhitespaceAnalyzer(Version.LUCENE_41)
  val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)

  val ramDir = new RAMDirectory
  val indexReader = {
    val writer = new IndexWriter(ramDir, config)
    val texts = List("A book on machine learning and machine translation",
    				"A book on machine learning and a book on machine translation",
    				"A book on statistics and machine translation",
    				"A book on statistics and machine reading",
    				"A book")
   texts.foreach{ text =>
     val doc = new Document()
     doc.add(new Field("c", text, TextField.TYPE_NOT_STORED))
     writer.addDocument(doc)
   }
    writer.commit()
    writer.close()
    DirectoryReader.open(ramDir)
  }

  val reader = new SlowCompositeReaderWrapper(indexReader)
  val readerContextLeaves = reader.leaves()
  val readerContext = readerContextLeaves.get(0)
  val searcher = new IndexSearcher(reader)

  val queryTerms = Seq(new Term("c", "machine"), new Term("c", "learning"), new Term("c", "and"), new Term("c", "machine"), new Term("c", "translation"))
  "PhraseProximityQuery" should {
    val q = PhraseProximityQuery(queryTerms, phrases)

    var weight = searcher.createNormalizedWeight(q)

      var scorer = weight.scorer(readerContext, true, true, reader.getLiveDocs)
      val buf = new ArrayBuffer[(Int, Float)]()
      var doc = scorer.nextDoc()
      while (doc < DocIdSetIterator.NO_MORE_DOCS) {
        buf += ((doc, scorer.score()))
        doc = scorer.nextDoc()
      }
      indexReader.numDocs() === 5
      buf.size === 4
      buf.sortBy(_._2).map(_._1) === Seq(3, 2, 1, 0)        // doc 0 is most relevant
  }

  "not return hits when no term and phrase" in {
      val q = PhraseProximityQuery(Seq.empty[Term], Set.empty[(Int, Int)])
      val weight = searcher.createNormalizedWeight(q)
      (weight != null) === true

      val scorer = weight.scorer(readerContext, true, true, reader.getLiveDocs)
      scorer === null
    }
}
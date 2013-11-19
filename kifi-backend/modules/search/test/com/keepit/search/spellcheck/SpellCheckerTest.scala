package com.keepit.search.spellcheck

import org.specs2.mutable.Specification
import com.keepit.search.index.VolatileIndexDirectoryImpl
import com.keepit.search.index.DefaultAnalyzer
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.document.Document
import org.apache.lucene.document.StringField
import org.apache.lucene.document.Field
import org.apache.lucene.document.FieldType
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.AtomicReader
import org.apache.lucene.index.SlowCompositeReaderWrapper
import org.apache.lucene.util.BytesRef
import org.apache.lucene.search.DocIdSetIterator

class SpellCheckerTest extends Specification {

  val articles = Seq("abc abc abc def", "abc def", "abc abd deg xyz")
  val analyzer = DefaultAnalyzer.forIndexing

  def mkDoc(content: String) = {
    val doc = new Document()
    val ts = analyzer.createLazyTokenStream("c", content)
    doc.add(new Field("c", ts, new FieldType(TextField.TYPE_NOT_STORED)))
    doc
  }

  "spell correcter" should {
    "work" in {
      val articleIndexDir = new VolatileIndexDirectoryImpl()
      val spellIndexDir = new VolatileIndexDirectoryImpl()
      val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)
      val spellIndexer = SpellIndexer(spellIndexDir, articleIndexDir, SpellCheckerConfig(0f, "lev"))
      val corrector = new SpellCorrectorImpl(spellIndexer, enableAdjScore = false)

      val indexWriter = new IndexWriter(articleIndexDir, config)
      articles.foreach{ x => indexWriter.addDocument(mkDoc(x)) }
      indexWriter.close()

      spellIndexer.getSpellChecker.exist("abc") === false
      spellIndexer.buildDictionary()
      spellIndexer.getSpellChecker.exist("abc") === true
      spellIndexer.getSpellChecker.exist("xyz") === true
      corrector.getSuggestions("abcd deh", 2).toSet === Set("abc def", "abd def", "abc deg", "abd deg")

      corrector.getScoredSuggestions("abcd deh", 2, enableBoost = false).head.value === "abc def"      // win by co-occurrence rate
    }
  }

  "SuggestionScorer" should {
    "work" in {
      val articleIndexDir = new VolatileIndexDirectoryImpl()
      val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)

      val indexWriter = new IndexWriter(articleIndexDir, config)
      articles.foreach{ x => indexWriter.addDocument(mkDoc(x)) }
      indexWriter.close()

      val statsReader = new TermStatsReaderImpl(articleIndexDir, "c")
      val scorer = new SuggestionScorer(statsReader, enableAdjScore = false)
      var s = Suggest("abc")
      scorer.score(s).score === 3f
      s = Suggest("def deg")
      scorer.score(s).score === 1f          // zero intersection, smoothed to 1
    }

    "adjScore should work" in {
      val articleIndexDir = new VolatileIndexDirectoryImpl()
      val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)
      val indexWriter = new IndexWriter(articleIndexDir, config)
      val texts = Seq("ab x1 x2 x3 x4 cd", "ab x1 x2 x3 x4 x5 x6 cd", "ab ab x1 x2 ab ab")
      texts.foreach{ x => indexWriter.addDocument(mkDoc(x)) }
      indexWriter.close()

      val statsReader = new TermStatsReaderImpl(articleIndexDir, "c")
      val scorer = new SuggestionScorer(statsReader, enableAdjScore = true)

      val score = scorer.score(Suggest("ab cd")).score
      val numInter = 2
      val avgDist = (4 + 6)/2
      (score - numInter * scorer.gaussianScore(avgDist)).max(1e-5) === 1e-5
    }
  }

  "MetaphoneDistance" should {
    "work" in {
      val mp = new MetaphoneDistance()
      mp.getDistance("apple", "aple") === 1f
      (mp.getDistance("aple", "able") - 2/3f).max(1e-5f) === 1e-5f
    }
  }

}

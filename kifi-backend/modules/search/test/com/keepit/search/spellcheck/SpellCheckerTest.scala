package com.keepit.search.spellcheck

import org.specs2.mutable.Specification
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.FieldType
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.VolatileIndexDirectory
import scala.math.abs

class SpellCheckerTest extends Specification {

  val articles = Seq("abc abc abc def", "abc def", "abc abd deg xyz")
  val analyzer = DefaultAnalyzer.defaultAnalyzer

  def mkDoc(content: String) = {
    val doc = new Document()
    val ts = analyzer.createLazyTokenStream("c", content)
    doc.add(new Field("c", ts, new FieldType(TextField.TYPE_NOT_STORED)))
    doc
  }

  "spell correcter" should {
    "work" in {
      val articleIndexDir = new VolatileIndexDirectory()

      val spellIndexDir = new VolatileIndexDirectory()
      val config = new IndexWriterConfig(Version.LUCENE_47, analyzer)
      val spellIndexer: SpellIndexer = new SpellIndexerImpl(spellIndexDir, SpellCheckerConfig(0f, "lev")) {
        protected def getIndexReader() = DirectoryReader.open(articleIndexDir)
      }
      var corrector = new SpellCorrectorImpl(spellIndexer, "slow", enableAdjScore = false)

      val indexWriter = new IndexWriter(articleIndexDir, new IndexWriterConfig(Version.LUCENE_47, analyzer))
      articles.foreach { x => indexWriter.addDocument(mkDoc(x)) }
      indexWriter.close()

      spellIndexer.getSpellChecker.exist("abc") === false
      spellIndexer.buildDictionary()
      spellIndexer.getSpellChecker.exist("abc") === true
      spellIndexer.getSpellChecker.exist("xyz") === true
      corrector.getScoredSuggestions("abcd deh", 2, enableBoost = false).map { _.value }.toSet === Set("abc def", "abd def", "abc deg", "abd deg")
      corrector.getScoredSuggestions("abcd deh", 2, enableBoost = false).head.value === "abc def" // win by co-occurrence rate

      corrector = new SpellCorrectorImpl(spellIndexer, "viterbi", enableAdjScore = false)
      corrector.getScoredSuggestions("abcd deh", 2, enableBoost = false).head.value === "abc def"
    }
  }

  "MetaphoneDistance" should {
    "work" in {
      val mp = new MetaphoneDistance()
      mp.getDistance("apple", "aple") === 1f
      abs(mp.getDistance("aple", "able") - 2 / 3f) < (1e-5f) === true
    }
  }

}

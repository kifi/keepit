package com.keepit.search.spellcheck

import org.specs2.mutable.Specification
import com.keepit.search.index.DefaultAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.FieldType
import org.apache.lucene.document.TextField
import com.keepit.search.index.VolatileIndexDirectoryImpl
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import org.apache.lucene.index.IndexWriter

class TermStatsReaderTest extends Specification {

  val articles = Seq("abc abc abc", "abc def", "abc abd deg xyz")
  val analyzer = DefaultAnalyzer.forIndexing

  def mkDoc(content: String) = {
    val doc = new Document()
    val ts = analyzer.createLazyTokenStream("c", content)
    doc.add(new Field("c", ts, new FieldType(TextField.TYPE_NOT_STORED)))
    doc
  }

  "TermStatsReader" should {
    "read simple stats" in {
      val articleIndexDir = new VolatileIndexDirectoryImpl()
      val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)

      val indexWriter = new IndexWriter(articleIndexDir, config)
      articles.foreach{ x => indexWriter.addDocument(mkDoc(x)) }
      indexWriter.close()

      val statsReader = new TermStatsReaderImpl(articleIndexDir, "c")
      var stats = statsReader.getSimpleTermStats("abc")
      stats.docFreq === 3
      stats.docIds === Set(0,1,2)

      stats = statsReader.getSimpleTermStats("xyz")
      stats.docFreq === 1
      stats.docIds === Set(2)
    }

    "retrieve docs and positions for liveDocs" in {

      val articleIndexDir = new VolatileIndexDirectoryImpl()
      val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)

      val indexWriter = new IndexWriter(articleIndexDir, config)
      articles.foreach{ x => indexWriter.addDocument(mkDoc(x)) }
      indexWriter.close()

      val statsReader = new TermStatsReaderImpl(articleIndexDir, "c")

      var docsAndPos = statsReader.getDocsAndPositions("abc", null)
      docsAndPos.size === 3
      docsAndPos(0) === Array(0, 1, 2)
      docsAndPos(1) === Array(0)
      docsAndPos(2) === Array(0)

      val liveDocs = TermStatsReader.genBits(Set(0, 2))
      docsAndPos = statsReader.getDocsAndPositions("abc", liveDocs)
      docsAndPos.size === 2
      docsAndPos(0) === Array(0, 1, 2)
      docsAndPos(2) === Array(0)

    }
  }
}

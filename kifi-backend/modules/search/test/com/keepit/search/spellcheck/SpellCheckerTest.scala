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

  val articles = Seq("abc abc abc", "abc def", "abc abd deg xyz")
  val analyzer = DefaultAnalyzer.forIndexing

  def mkDoc(content: String) = {
    val doc = new Document()
    val ts = analyzer.createLazyTokenStream("c", content)
    doc.add(new Field("c", ts, new FieldType(TextField.TYPE_NOT_STORED)))
    doc
  }

  "spell correcter" should {
    "build dict from exisiting Lucene index" in {
      val articleIndexDir = new VolatileIndexDirectoryImpl()
      val spellIndexDir = new VolatileIndexDirectoryImpl()
      val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)
      val spellIndexer = SpellIndexer(spellIndexDir, articleIndexDir, SpellIndexerConfig(0f))
      val corrector = new SpellCorrectorImpl(spellIndexer)

      val indexWriter = new IndexWriter(articleIndexDir, config)
      articles.foreach{ x => indexWriter.addDocument(mkDoc(x)) }
      indexWriter.close()

      spellIndexer.getSpellChecker.exist("abc") === false
      spellIndexer.buildDictionary()
      spellIndexer.getSpellChecker.exist("abc") === true
      spellIndexer.getSpellChecker.exist("xyz") === true
      corrector.getSuggestions("abcd deh", 2).toSet === Set("abc def", "abd def", "abc deg", "abd deg")
    }

    "get access to term stats" in {
      val articleIndexDir = new VolatileIndexDirectoryImpl()
      val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)

      val indexWriter = new IndexWriter(articleIndexDir, config)
      articles.foreach{ x => indexWriter.addDocument(mkDoc(x)) }
      indexWriter.close()

      val reader = new SlowCompositeReaderWrapper(DirectoryReader.open(articleIndexDir))
      val fields = reader.fields()
      fields.size() === 1
      val terms = fields.terms("c")
      val termsEnum = terms.iterator(null)
      val found = termsEnum.seekExact(new BytesRef("abc"), true)
      found === true
      termsEnum.docFreq() === 3
      val docs = termsEnum.docs(null, null)
      var docid = docs.nextDoc()
      while (docid != DocIdSetIterator.NO_MORE_DOCS){
        println("\n======\n")
        println(s"docid = ${docid}")
        println(s"freq = ${docs.freq}")
        docid = docs.nextDoc()
      }

    }
  }
}

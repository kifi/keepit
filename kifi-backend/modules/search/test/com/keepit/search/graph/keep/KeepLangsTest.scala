package com.keepit.search.graph.keep

import com.keepit.common.strings._
import com.keepit.search.Searcher
import com.keepit.search.index.Indexable.DataPayloadTokenStream
import com.keepit.search.index.{ Indexer, Indexable, DefaultAnalyzer }
import org.apache.lucene.document.{ NumericDocValuesField, Field, Document }
import org.apache.lucene.index._
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.util.Version
import org.specs2.mutable.Specification
import scala.util.Random

class KeepLangsTest extends Specification {

  private[this] val config = new IndexWriterConfig(Version.LATEST, DefaultAnalyzer.defaultAnalyzer)

  private[this] val langs1 = Seq(
    "es", "es", "es", "es", "es", "es", "es", "es", "es", "es", // 10x
    "en", "en", "en", "en", "en", "en", "en", "en", // 8x
    "ru", "ru", "ru", "ru", "ru", // 5x
    "fr",
    "de"
  )

  private[this] val langs2 = Seq(
    "en", "en", "en", "en", "en", "en", "en", "en", "en", "en", "en", "en", "en", "en", "en", "en", "en", "en", "en", "en",
    "fr",
    "de",
    "ja"
  )

  def buildDataPayloadField(term: Term, data: Array[Byte]): Field = {
    new Field(term.field(), new DataPayloadTokenStream(term.text(), data), Indexable.dataPayloadFieldType)
  }

  private[this] var id: Long = 1
  private[this] val ramDir = new RAMDirectory
  private[this] val indexReader = {
    val rnd = new Random
    val writer = new IndexWriter(ramDir, config)
    rnd.shuffle(langs1).foreach { lang =>
      val doc = new Document()
      doc.add(new NumericDocValuesField(Indexer.idValueFieldName, id))
      doc.add(buildDataPayloadField(new Term(KeepFields.libraryField, 1L.toString), lang.getBytes(UTF8)))
      writer.addDocument(doc)
      id += 1
    }
    rnd.shuffle(langs2).foreach { lang =>
      val doc = new Document()
      doc.add(new NumericDocValuesField(Indexer.idValueFieldName, id))
      doc.add(buildDataPayloadField(new Term(KeepFields.libraryField, 2L.toString), lang.getBytes(UTF8)))
      writer.addDocument(doc)
      id += 1
    }
    writer.commit()
    writer.close()

    DirectoryReader.open(ramDir)
  }

  private[this] val searcher = Searcher(indexReader)

  "KeepLangs" should {

    "find frequent languages" in {
      val keepLangs = new KeepLangs(searcher)
      keepLangs.processLibraries(Set(1L))
      keepLangs.getFrequentLangs().toMap === Map("es" -> 10, "en" -> 8, "ru" -> 5)
    }

    "exclude low frequency languages" in {
      val keepLangs = new KeepLangs(searcher)
      keepLangs.processLibraries(Set(2L))
      keepLangs.getFrequentLangs().toMap === Map("en" -> 20)
    }
  }
}

package com.keepit.search.query

import org.specs2.mutable._
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.util.Version
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.search.index._
import com.keepit.search.SemanticVectorBuilder
import java.io.StringReader
import scala.Some

class SemanticVectorQueryTest extends Specification {

  class Tst(val id: Id[Tst], val text: String, val fallbackText: String)

  class TstIndexer(indexDirectory: VolatileIndexDirectoryImpl, indexWriterConfig: IndexWriterConfig)
    extends Indexer[Tst](indexDirectory, indexWriterConfig) {

    class TstIndexable[Tst](override val id: Id[Tst], val text: String, val fallbackText: String) extends Indexable[Tst] {

      implicit def toReader(text: String) = new StringReader(text)

      override val sequenceNumber = SequenceNumber.ZERO
      override val isDeleted = false

      override def buildDocument = {
        val doc = super.buildDocument
        val analyzer = indexWriterConfig.getAnalyzer
        val content = buildTextField("c", text)
        val fallback = buildTextField("fallback", fallbackText)
        val builder = new SemanticVectorBuilder(60)
        builder.load( analyzer.tokenStream("c", text) )
        val semanticVector = buildSemanticVectorField("sv", builder)
        val docSemanticVector = buildDocSemanticVectorField("docSV", builder)
        doc.add(content)
        doc.add(fallback)
        doc.add(semanticVector)
        doc.add(docSemanticVector)
        doc
      }
    }

    def buildIndexable(id: Id[Tst]): Indexable[Tst] = throw new UnsupportedOperationException()
    def buildIndexable(data: Tst): Indexable[Tst] = new TstIndexable(data.id, data.text, data.fallbackText)

    def index(id: Id[Tst], text: String, fallbackText: String) = {
      indexDocuments(Some(buildIndexable(new Tst(id, text, fallbackText))).iterator, 100)
    }

    def getPersonalizedSeacher(ids: Set[Long]) = PersonalizedSearcher(searcher, ids)
  }

  val indexingAnalyzer = DefaultAnalyzer.forIndexing
  val config = new IndexWriterConfig(Version.LUCENE_41, indexingAnalyzer)

  val indexer = new TstIndexer(new VolatileIndexDirectoryImpl, config)
  Array("abc", "abc def", "abc def ghi", "def ghi").zip(Array("", "", "", "jkl")).zipWithIndex.map{ case ((text, fallbackText), id) =>
    indexer.index(Id[Tst](id), text, fallbackText)
  }

  "SemanticVectorQuery" should {
    "score using a personalized vector" in {
      var q = SemanticVectorQuery(new Term("sv", "abc"))

      val searcher0 = indexer.getPersonalizedSeacher(Set(0L))
      val searcher1 = indexer.getPersonalizedSeacher(Set(1L))
      val searcher2 = indexer.getPersonalizedSeacher(Set(2L))
      val searcher3 = indexer.getPersonalizedSeacher(Set(3L))
      searcher0.search(q).head.id === 0
      searcher1.search(q).head.id === 1
      searcher2.search(q).head.id === 2
      searcher3.search(q).head.id === 0
    }
  }
}

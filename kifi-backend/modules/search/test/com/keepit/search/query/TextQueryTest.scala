package com.keepit.search.query

import org.specs2.mutable.Specification
import com.keepit.common.db.Id
import org.apache.lucene.index.IndexWriterConfig
import com.keepit.search.index._
import java.io.StringReader
import com.keepit.common.db.SequenceNumber
import org.apache.lucene.util.Version
import org.apache.lucene.search.TermQuery
import org.apache.lucene.index.Term
import com.keepit.search.SemanticVectorBuilder
import scala.Some

class TextQueryTest extends Specification {

  class Tst(val id: Id[Tst], val text: String, val personalText: String)

  class TstIndexer(indexDirectory: IndexDirectory, indexWriterConfig: IndexWriterConfig)
    extends Indexer[Tst](indexDirectory, indexWriterConfig) {

    class TstIndexable[Tst](override val id: Id[Tst], val text: String, val personalText: String) extends Indexable[Tst] {

      implicit def toReader(text: String) = new StringReader(text)

      override val sequenceNumber = SequenceNumber.ZERO
      override val isDeleted = false

      override def buildDocument = {
        val doc = super.buildDocument
        val analyzer = indexWriterConfig.getAnalyzer
        val content = buildTextField("c", text)
        val personal = buildTextField("p", personalText)
        val builder = new SemanticVectorBuilder(60)
        builder.load( analyzer.tokenStream("c", text) )
        val semanticVector = buildSemanticVectorField("sv", builder)
        val docSemanticVector = buildDocSemanticVectorField("docSV", builder)
        doc.add(content)
        doc.add(personal)
        doc.add(semanticVector)
        doc.add(docSemanticVector)
        doc
      }
    }

    def buildIndexable(id: Id[Tst]): Indexable[Tst] = throw new UnsupportedOperationException()
    def buildIndexable(data: Tst): Indexable[Tst] = new TstIndexable(data.id, data.text, data.personalText)

    def index(id: Id[Tst], text: String, fallbackText: String) = {
      indexDocuments(Some(buildIndexable(new Tst(id, text, fallbackText))).iterator, 100)
    }

    def getPersonalizedSearcher(ids: Set[Long]) = PersonalizedSearcher(searcher, ids)
  }

  val indexingAnalyzer = DefaultAnalyzer.forIndexing
  val config = new IndexWriterConfig(Version.LUCENE_41, indexingAnalyzer)

  val indexer = new TstIndexer(new VolatileIndexDirectoryImpl, config)
  Array("abc def", "abc def", "abc def", "abc ghi", "abc jkl").zip(Array("", "", "", "mno", "mno")).zipWithIndex.map{ case ((text, fallbackText), id) =>
    indexer.index(Id[Tst](id), text, fallbackText)
  }

  "TextQuery" should {
    "not fail even when there is no subquery" in {
      val q = new TextQuery
      indexer.getPersonalizedSearcher(Set(0L)).search(q).map(_.id).toSet  === Set.empty[Long]
    }

    "search using regular query" in {
      val q0 = new TextQuery
      q0.addRegularQuery(new TermQuery(new Term("c", "def")))
      indexer.getPersonalizedSearcher(Set(0L)).search(q0).map(_.id).toSet === Set(0L, 1L, 2L)

      val q1 = new TextQuery
      q1.addRegularQuery(new TermQuery(new Term("c", "def")))
      q1.addRegularQuery(new TermQuery(new Term("c", "ghi")))
      indexer.getPersonalizedSearcher(Set(0L)).search(q1).map(_.id).toSet === Set(0L, 1L, 2L, 3L)
    }

    "search using personal query" in {
      val q0 = new TextQuery
      q0.addPersonalQuery(new TermQuery(new Term("c", "def")))
      indexer.getPersonalizedSearcher(Set(0L)).search(q0).map(_.id).toSet === Set(0L, 1L, 2L)

      val q1 = new TextQuery
      q1.addPersonalQuery(new TermQuery(new Term("c", "def")))
      q1.addPersonalQuery(new TermQuery(new Term("c", "ghi")))
      indexer.getPersonalizedSearcher(Set(0L)).search(q1).map(_.id).toSet === Set(0L, 1L, 2L, 3L)
    }

    "search using both regular and personal query" in {
      val q0 = new TextQuery
      q0.addRegularQuery(new TermQuery(new Term("c", "def")))
      q0.addPersonalQuery(new TermQuery(new Term("c", "ghi")))
      indexer.getPersonalizedSearcher(Set(0L)).search(q0).map(_.id).toSet === Set(0L, 1L, 2L, 3L)
    }

    "score using regular query and semantic vector query" in {
      val q0 = new TextQuery
      q0.addRegularQuery(new TermQuery(new Term("c", "abc")))
      q0.setSemanticBoost(1.0f)
      q0.addSemanticVectorQuery("sv", "abc")
      indexer.getPersonalizedSearcher(Set(3L)).search(q0).head.id === 3L
      indexer.getPersonalizedSearcher(Set(4L)).search(q0).head.id === 4L
    }

    "score using personal query and semantic vector query" in {
      val q0 = new TextQuery
      q0.addPersonalQuery(new TermQuery(new Term("c", "abc")))
      q0.setSemanticBoost(1.0f)
      q0.addSemanticVectorQuery("sv", "abc")
      indexer.getPersonalizedSearcher(Set(3L)).search(q0).head.id === 3L
      indexer.getPersonalizedSearcher(Set(4L)).search(q0).head.id === 4L
    }

    "score using all queries" in {
      val q0 = new TextQuery
      q0.addRegularQuery(new TermQuery(new Term("c", "abc")))
      q0.addPersonalQuery(new TermQuery(new Term("p", "xyz")))
      q0.setSemanticBoost(1.0f)
      q0.addSemanticVectorQuery("sv", "mno")
      indexer.getPersonalizedSearcher(Set(0L)).search(q0).head.id === 0L

      val q1 = new TextQuery
      q1.addRegularQuery(new TermQuery(new Term("c", "abc")))
      q1.addPersonalQuery(new TermQuery(new Term("p", "mno")))
      q1.setSemanticBoost(1.0f)
      q1.addSemanticVectorQuery("sv", "mno")
      indexer.getPersonalizedSearcher(Set(0L)).search(q1).take(2).map(_.id).toSet === Set(3L, 4L)
    }
  }
}

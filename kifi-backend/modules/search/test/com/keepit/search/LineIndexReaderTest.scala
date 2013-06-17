package com.keepit.search.line

import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import scala.math._
import scala.collection.mutable.ArrayBuffer
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.BooleanQueryWithPercentMatch
import org.apache.lucene.document.Document
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.Term
import org.apache.lucene.index.SlowCompositeReaderWrapper
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.similarities.DefaultSimilarity
import org.apache.lucene.search.WildcardQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.util.Version
import java.io.StringReader
import org.apache.lucene.index.AtomicReader
import com.keepit.search.Lang

class LineIndexReaderTest extends Specification {

  val indexingAnalyzer = DefaultAnalyzer.forIndexing
  val config = new IndexWriterConfig(Version.LUCENE_41, indexingAnalyzer)

  val ramDir = new RAMDirectory
  val reader = new SlowCompositeReaderWrapper(populateIndex)

  def populateIndex: DirectoryReader = {
    val lineFieldBuilder = new LineFieldBuilder {}
    val writer = new IndexWriter(ramDir, config)
    (0 until 3).foreach{ d =>
      val lines = new ArrayBuffer[(Int, String, Lang)]
      (0 until 3).foreach{ l =>
        val line = (l, "d%d l%d t%d%d %s %s".format(d, l, d, l, " x"*(1 + (l + d)%3), " y"*(4 - (l + d)%3)), Lang("en"))
        //println(line)
        lines += line
      }
      val doc = new Document()
      doc.add(lineFieldBuilder.buildLineField("B", lines){ (f, t, l) =>
        indexingAnalyzer.tokenStream(f, new StringReader(t))
      })
      writer.addDocument(doc)
    }
    writer.commit()
    writer.close()

    DirectoryReader.open(ramDir)
  }

  def doQuery(query: Query, ir: AtomicReader) = {
    val searcher = new IndexSearcher(ir)
    var weight = searcher.createNormalizedWeight(query)
    (weight != null) === true

    var scorer = weight.scorer(ir.getContext, true, true, ir.getLiveDocs)
    val buf = new ArrayBuffer[(Int, Float)]()
    if (scorer != null) {
      var doc = scorer.nextDoc()
      while (doc < DocIdSetIterator.NO_MORE_DOCS) {
        buf += ((doc, scorer.score()))
        doc = scorer.nextDoc()
      }
    }
    buf
  }

  "LineIndexReader" should {

    "find lines using term query" in {
      var ir = LineIndexReader(reader, 1, Set(new Term("B", "t10"), new Term("B", "t22")), 3)

      var q = new TermQuery(new Term("B", "t10"))
      var res = doQuery(q, ir)
      res.size === 1
      res(0)._1 === 0

      q = new TermQuery(new Term("B", "t22"))
      res = doQuery(q, ir)
      res.size === 0

      ir = LineIndexReader(reader, 2, Set(new Term("B", "t22")), 3)

      q = new TermQuery(new Term("B", "t22"))
      res = doQuery(q, ir)
      res.size === 1
      res(0)._1 === 2
    }

    "find lines with boolean query" in {
      var ir = LineIndexReader(reader, 1, Set(new Term("B", "l1"), new Term("B", "l2"), new Term("B", "t12")), 3)

      var q = new BooleanQuery
      q.add(new TermQuery(new Term("B", "l1")), BooleanClause.Occur.MUST)
      q.add(new TermQuery(new Term("B", "t12")), BooleanClause.Occur.MUST)

      var res = doQuery(q, ir)
      res.size === 0

      q = new BooleanQuery
      q.add(new TermQuery(new Term("B", "l2")), BooleanClause.Occur.MUST)
      q.add(new TermQuery(new Term("B", "t12")), BooleanClause.Occur.MUST)

      res = doQuery(q, ir)
      res.size === 1
      res(0)._1 === 2
    }

    "score term query" in {
      val qx = new TermQuery(new Term("B", "x"))
      val qy = new TermQuery(new Term("B", "y"))

      var ir = LineIndexReader(reader, 0, Set(new Term("B", "x"), new Term("B", "y")), 3)
      var res = doQuery(qx, ir)
      res.size === 3
      res.sortWith((a, b) => (a._2 < b._2)).map(h => h._1) === ArrayBuffer(0, 1, 2)

      res = doQuery(qy, ir)
      res.size === 3
      res.sortWith((a, b) => (a._2 < b._2)).map(h => h._1) === ArrayBuffer(2, 1, 0)

      ir = LineIndexReader(reader, 1, Set(new Term("B", "x"), new Term("B", "y")), 3)
      res = doQuery(qx, ir)
      res.size === 3
      res.sortWith((a, b) => (a._2 < b._2)).map(h => h._1) === ArrayBuffer(2, 0, 1)

      res = doQuery(qy, ir)
      res.size === 3
      res.sortWith((a, b) => (a._2 < b._2)).map(h => h._1) === ArrayBuffer(1, 0, 2)

      ir = LineIndexReader(reader, 2, Set(new Term("B", "x"), new Term("B", "y")), 3)
      res = doQuery(qx, ir)
      res.size === 3
      res.sortWith((a, b) => (a._2 < b._2)).map(h => h._1) === ArrayBuffer(1, 2, 0)

      res = doQuery(qy, ir)
      res.size === 3
      res.sortWith((a, b) => (a._2 < b._2)).map(h => h._1) === ArrayBuffer(0, 2, 1)
    }
  }
}

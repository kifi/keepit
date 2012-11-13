package com.keepit.search.line

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import scala.math._
import scala.collection.mutable.ArrayBuffer
import org.apache.lucene.document.Document
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.index.TermEnum
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.DefaultSimilarity
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.util.Version
import com.keepit.search.index.DefaultAnalyzer

@RunWith(classOf[JUnitRunner])
class LineQueryTest extends SpecificationWithJUnit {

  val analyzer = new DefaultAnalyzer
  val config = new IndexWriterConfig(Version.LUCENE_36, analyzer)

  val ramDir = new RAMDirectory
  implicit val indexReader = populateIndex
  val builder = new LineQueryBuilder(new DefaultSimilarity, 0.0f)
  
  def populateIndex: IndexReader = {
    val lineFieldBuilder = new LineFieldBuilder {}
    val writer = new IndexWriter(ramDir, config)
    (0 until 3).foreach{ d =>
      val lines = new ArrayBuffer[(Int, String)]
      (0 until 3).foreach{ l =>
        val line = (l, "d%d l%d t%d%d %s %s %s".format(d, l, d, l, " x"*d, " y"*l, " z"*(min(l + 1, 3 - d))))
        //println(line)
        lines += line
      }
      val doc = new Document()
      doc.add(lineFieldBuilder.buildLineField("B", lines, analyzer))
      writer.addDocument(doc)
    }
    writer.commit()
    writer.close()
    
    IndexReader.open(ramDir)
  }
  
  "LineQuery" should {
    
    "find docs using term query" in {
      var q = new TermQuery(new Term("B", "d1"))
      var plan = builder.build(q)
      plan.fetch(0) === 1
      plan.fetch(0) === LineQuery.NO_MORE_DOCS

      q = new TermQuery(new Term("B", "l1"))
      plan = builder.build(q)
      plan.fetch(0) === 0
      plan.fetch(0) === 1
      plan.fetch(0) === 2
      plan.fetch(0) === LineQuery.NO_MORE_DOCS
    }
    
    "find docs using phrase query" in {
      var q = new PhraseQuery()
      q.add(new Term("B", "d1"))
      q.add(new Term("B", "l1"))
      var plan = builder.build(q)
      plan.fetch(0) === 1
      plan.fetch(0) === LineQuery.NO_MORE_DOCS

      q = new PhraseQuery()
      q.add(new Term("B", "x"))
      q.add(new Term("B", "y"))
      plan = builder.build(q)
      plan.fetch(0) === 1
      plan.fetch(0) === 2
      plan.fetch(0) === LineQuery.NO_MORE_DOCS
    }
    
    "find docs using boolean query" in {
      var q = new BooleanQuery
      q.add(new TermQuery(new Term("B", "d1")), BooleanClause.Occur.SHOULD)
      q.add(new TermQuery(new Term("B", "d2")), BooleanClause.Occur.SHOULD)
      var plan = builder.build(q)
      plan.fetch(0) === 1
      plan.fetch(0) === 2
      plan.fetch(0) === LineQuery.NO_MORE_DOCS
      
      q = new BooleanQuery
      q.add(new TermQuery(new Term("B", "d1")), BooleanClause.Occur.SHOULD)
      q.add(new TermQuery(new Term("B", "d2")), BooleanClause.Occur.MUST)
      plan = builder.build(q)
      plan.fetch(0) === 2
      plan.fetch(0) === LineQuery.NO_MORE_DOCS
      
      q = new BooleanQuery
      q.add(new TermQuery(new Term("B", "d1")), BooleanClause.Occur.MUST)
      q.add(new TermQuery(new Term("B", "d2")), BooleanClause.Occur.MUST)
      plan = builder.build(q)
      plan.fetch(0) === LineQuery.NO_MORE_DOCS
      
      q = new BooleanQuery
      q.add(new TermQuery(new Term("B", "l0")), BooleanClause.Occur.MUST)
      q.add(new TermQuery(new Term("B", "l1")), BooleanClause.Occur.MUST)
      q.add(new TermQuery(new Term("B", "l2")), BooleanClause.Occur.MUST)
      q.add(new TermQuery(new Term("B", "d1")), BooleanClause.Occur.MUST_NOT)
      plan = builder.build(q)
      plan.fetch(0) === LineQuery.NO_MORE_DOCS
      
      q = new BooleanQuery
      q.add(new TermQuery(new Term("B", "l0")), BooleanClause.Occur.SHOULD)
      q.add(new TermQuery(new Term("B", "l1")), BooleanClause.Occur.SHOULD)
      q.add(new TermQuery(new Term("B", "l2")), BooleanClause.Occur.SHOULD)
      q.add(new TermQuery(new Term("B", "d1")), BooleanClause.Occur.MUST_NOT)
      plan = builder.build(q)
      plan.fetch(0) === 0
      plan.fetch(0) === 2
      plan.fetch(0) === LineQuery.NO_MORE_DOCS
    }
    
    "find lines using term query" in {
      var q = new TermQuery(new Term("B", "t10"))
      var plan = builder.build(q)
      plan.fetchDoc(0) === 1
      plan.fetchLine(0) === 0
      plan.fetchDoc(0) === LineQuery.NO_MORE_DOCS

      q = new TermQuery(new Term("B", "t22"))
      plan = builder.build(q)
      plan.fetchDoc(0) === 2
      plan.fetchLine(0) === 2
      plan.fetchDoc(0) === LineQuery.NO_MORE_DOCS
    }

    "find lines with boolean query" in {
      var q = new BooleanQuery
      q.add(new TermQuery(new Term("B", "l1")), BooleanClause.Occur.MUST)
      q.add(new TermQuery(new Term("B", "t12")), BooleanClause.Occur.MUST)
      var plan = builder.build(q)
      plan.fetchDoc(0) === 1
      plan.fetchLine(0) === LineQuery.NO_MORE_LINES
      plan.fetchDoc(0) === LineQuery.NO_MORE_DOCS
      
      q = new BooleanQuery
      q.add(new TermQuery(new Term("B", "l2")), BooleanClause.Occur.MUST)
      q.add(new TermQuery(new Term("B", "t12")), BooleanClause.Occur.MUST)
      plan = builder.build(q)
      plan.fetchDoc(0) === 1
      plan.fetchLine(0) === 2
      plan.fetchDoc(0) === LineQuery.NO_MORE_DOCS
    }
    
    "score term query" in {
      var q = new TermQuery(new Term("B", "x"))
      var hits = ArrayBuffer.empty[(Int, Int, Float)]
      var plan = builder.build(q)
      var doc = plan.fetchDoc(0)
      while (doc < LineQuery.NO_MORE_DOCS) {
        var line = plan.fetchLine(0)
        while (line < LineQuery.NO_MORE_LINES) {
          hits += ((doc, line, plan.score))
          line = plan.fetchLine(0)
        }
        doc = plan.fetchDoc(0)
      }
      hits.sortWith((a, b) => (a._3 < b._3)).map(h => (h._1, h._2)) === ArrayBuffer((1, 0), (1, 1), (1, 2), (2, 0), (2, 1), (2, 2))
      
      q = new TermQuery(new Term("B", "y"))
      hits = ArrayBuffer.empty[(Int, Int, Float)]
      plan = builder.build(q)
      doc = plan.fetchDoc(0)
      while (doc < LineQuery.NO_MORE_DOCS) {
        var line = plan.fetchLine(0)
        while (line < LineQuery.NO_MORE_LINES) {
          hits += ((doc, line, plan.score))
          line = plan.fetchLine(0)
        }
        doc = plan.fetchDoc(0)
      }
      hits.sortWith((a, b) => (a._3 < b._3)).map(h => (h._1, h._2)) === ArrayBuffer((0, 1), (1, 1), (2, 1), (0, 2), (1, 2), (2, 2))
      
      q = new TermQuery(new Term("B", "z"))
      hits = ArrayBuffer.empty[(Int, Int, Float)]
      plan = builder.build(q)
      doc = plan.fetchDoc(0)
      while (doc < LineQuery.NO_MORE_DOCS) {
        var line = plan.fetchLine(0)
        while (line < LineQuery.NO_MORE_LINES) {
          hits += ((doc, line, plan.score))
          line = plan.fetchLine(0)
        }
        doc = plan.fetchDoc(0)
      }
      hits.sortWith((a, b) => (a._3 < b._3)).map(h => (h._1, h._2)) ===
        ArrayBuffer((0, 0), (1, 0), (2, 0), (2, 1), (2, 2), (0, 1), (1, 1), (1, 2), (0, 2))
    }
    
    "score boolean query" in {
      var q = new BooleanQuery
      q.add(new TermQuery(new Term("B", "l0")), BooleanClause.Occur.SHOULD)
      q.add(new TermQuery(new Term("B", "t00")), BooleanClause.Occur.SHOULD)
      var hits = ArrayBuffer.empty[(Int, Int, Float)]
      var plan = builder.build(q)
      var doc = plan.fetchDoc(0)
      while (doc < LineQuery.NO_MORE_DOCS) {
        var line = plan.fetchLine(0)
        while (line < LineQuery.NO_MORE_LINES) {
          hits += ((doc, line, plan.score))
          line = plan.fetchLine(0)
        }
        doc = plan.fetchDoc(0)
      }
      hits.sortWith((a, b) => (a._3 < b._3)).map(h => (h._1, h._2)) === ArrayBuffer((1, 0), (2, 0), (0, 0))
      
      q = new BooleanQuery
      q.add(new TermQuery(new Term("B", "l0")), BooleanClause.Occur.MUST)
      q.add(new TermQuery(new Term("B", "t00")), BooleanClause.Occur.SHOULD)
      hits = ArrayBuffer.empty[(Int, Int, Float)]
      plan = builder.build(q)
      doc = plan.fetchDoc(0)
      while (doc < LineQuery.NO_MORE_DOCS) {
        var line = plan.fetchLine(0)
        while (line < LineQuery.NO_MORE_LINES) {
          hits += ((doc, line, plan.score))
          line = plan.fetchLine(0)
        }
        doc = plan.fetchDoc(0)
      }
      hits.sortWith((a, b) => (a._3 < b._3)).map(h => (h._1, h._2)) === ArrayBuffer((1, 0), (2, 0), (0, 0))
      
      q = new BooleanQuery
      q.add(new TermQuery(new Term("B", "d0")), BooleanClause.Occur.MUST)
      q.add(new TermQuery(new Term("B", "l0")), BooleanClause.Occur.SHOULD)
      q.add(new TermQuery(new Term("B", "l1")), BooleanClause.Occur.SHOULD)
      q.add(new TermQuery(new Term("B", "t00")), BooleanClause.Occur.SHOULD)
      hits = ArrayBuffer.empty[(Int, Int, Float)]
      plan = builder.build(q)
      doc = plan.fetchDoc(0)
      while (doc < LineQuery.NO_MORE_DOCS) {
        var line = plan.fetchLine(0)
        while (line < LineQuery.NO_MORE_LINES) {
          hits += ((doc, line, plan.score))
          line = plan.fetchLine(0)
        }
        doc = plan.fetchDoc(0)
      }
      hits.sortWith((a, b) => (a._3 < b._3)).map(h => (h._1, h._2)) === ArrayBuffer((0, 2), (0, 1), (0, 0))
      
      q = new BooleanQuery
      q.add(new TermQuery(new Term("B", "d0")), BooleanClause.Occur.SHOULD)
      q.add(new TermQuery(new Term("B", "l0")), BooleanClause.Occur.SHOULD)
      q.add(new TermQuery(new Term("B", "t01")), BooleanClause.Occur.SHOULD)
      hits = ArrayBuffer.empty[(Int, Int, Float)]
      plan = builder.build(q)
      doc = plan.fetchDoc(0)
      while (doc < LineQuery.NO_MORE_DOCS) {
        var line = plan.fetchLine(0)
        while (line < LineQuery.NO_MORE_LINES) {
          hits += ((doc, line, plan.score))
          line = plan.fetchLine(0)
        }
        doc = plan.fetchDoc(0)
      }
      hits.sortWith((a, b) => (a._3 < b._3)).map(h => (h._1, h._2)) === 
        ArrayBuffer((1, 0), (2, 0), (0, 2), (0, 0), (0, 1))
    }
    
    "honor percentMatch in BooleanNode" in {
      val builderWithPctMatch = new LineQueryBuilder(new DefaultSimilarity, 99.0f)
      var q = new BooleanQuery
      q.add(new TermQuery(new Term("B", "d2")), BooleanClause.Occur.MUST)
      q.add(new TermQuery(new Term("B", "l2")), BooleanClause.Occur.SHOULD)
      q.add(new TermQuery(new Term("B", "x")), BooleanClause.Occur.SHOULD)
      var hits = ArrayBuffer.empty[(Int, Int)]
      var plan = builder.build(q) // no percentMatch
      var doc = plan.fetchDoc(0)
      while (doc < LineQuery.NO_MORE_DOCS) {
        var line = plan.fetchLine(0)
        while (line < LineQuery.NO_MORE_LINES) {
          hits += ((doc, line))
          line = plan.fetchLine(0)
        }
        doc = plan.fetchDoc(0)
      }
      hits === ArrayBuffer((2, 0), (2, 1), (2, 2))
      
      hits = ArrayBuffer.empty[(Int, Int)]
      plan = builderWithPctMatch.build(q)
      doc = plan.fetchDoc(0)
      while (doc < LineQuery.NO_MORE_DOCS) {
        var line = plan.fetchLine(0)
        while (line < LineQuery.NO_MORE_LINES) {
          hits += ((doc, line))
          line = plan.fetchLine(0)
        }
        doc = plan.fetchDoc(0)
      }
      hits === ArrayBuffer((2, 2))
    }
    
    "honor percentMatch in BooleanOrNode" in {
      val builderWithPctMatch = new LineQueryBuilder(new DefaultSimilarity, 99.0f)
      var q = new BooleanQuery
      q.add(new TermQuery(new Term("B", "d2")), BooleanClause.Occur.SHOULD)
      q.add(new TermQuery(new Term("B", "l2")), BooleanClause.Occur.SHOULD)
      q.add(new TermQuery(new Term("B", "x")), BooleanClause.Occur.SHOULD)
      var hits = ArrayBuffer.empty[(Int, Int)]
      var plan = builder.build(q) // no percentMatch
      var doc = plan.fetchDoc(0)
      while (doc < LineQuery.NO_MORE_DOCS) {
        var line = plan.fetchLine(0)
        while (line < LineQuery.NO_MORE_LINES) {
          hits += ((doc, line))
          line = plan.fetchLine(0)
        }
        doc = plan.fetchDoc(0)
      }
      hits === ArrayBuffer((0, 2), (1, 0), (1, 1), (1, 2), (2, 0), (2, 1), (2, 2))
      
      hits = ArrayBuffer.empty[(Int, Int)]
      plan = builderWithPctMatch.build(q)
      doc = plan.fetchDoc(0)
      while (doc < LineQuery.NO_MORE_DOCS) {
        var line = plan.fetchLine(0)
        while (line < LineQuery.NO_MORE_LINES) {
          hits += ((doc, line))
          line = plan.fetchLine(0)
        }
        doc = plan.fetchDoc(0)
      }
      hits === ArrayBuffer((2, 2))
    }
  }
}

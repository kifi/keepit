package com.keepit.search.engine.query.core

import com.keepit.common.db.Id
import com.keepit.search.index.VolatileIndexDirectory
import com.keepit.search.{ Tst, TstIndexer }
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.{ TermQuery, Weight }
import org.specs2.mutable.Specification

import scala.collection.mutable.ArrayBuffer

class KWeightTest extends Specification {

  private val indexer = new TstIndexer(new VolatileIndexDirectory)
  Array("abc def", "abc def", "abc def", "abc ghi", "abc jkl").zipWithIndex.foreach {
    case (text, id) => indexer.index(Id[Tst](id), text, "")
  }
  private val searcher = indexer.getSearcher

  private def mkKTextQuery(tok: String): KTextQuery = {
    val tq = new KTextQuery(tok)
    tq.addQuery(new TermQuery(new Term("c", tok)))
    tq
  }

  "KWeight" should {
    "return a single (KTextWeight, 1.0f) for KTextQuery" in {
      val q0 = new KTextQuery("")
      val w0 = searcher.createWeight(q0).asInstanceOf[KWeight]
      val out = new ArrayBuffer[(Weight, Float)]
      w0.getWeights(out)

      out.size === 1
      out(0)._1 must beAnInstanceOf[KTextWeight]

      val q1 = mkKTextQuery("def")
      val w1 = searcher.createWeight(q1).asInstanceOf[KWeight]
      out.clear()
      w1.getWeights(out)

      out.size === 1
      out(0)._1 must beAnInstanceOf[KTextWeight]
    }

    "return an empty list of Weight for an empty KBooleanQuery" in {
      val q = new KBooleanQuery
      val w = searcher.createWeight(q).asInstanceOf[KWeight]
      val out = new ArrayBuffer[(Weight, Float)]
      w.getWeights(out)

      out.size === 0
    }

    "return a list of (Weight, value) for a KBooleanQuery (SHOULD, SHOULD, SHOULD)" in {
      val q = new KBooleanQuery
      q.add(mkKTextQuery("ghi"), Occur.SHOULD)
      q.add(mkKTextQuery("abc"), Occur.SHOULD)
      q.add(mkKTextQuery("def"), Occur.SHOULD)

      val w = searcher.createWeight(q).asInstanceOf[KWeight]
      val out = new ArrayBuffer[(Weight, Float)]
      w.getWeights(out)

      out.size === 3
      out.forall { case (w, v) => w.isInstanceOf[KWeight] && v > 0.0f } === true
      (out(0)._2 > out(1)._2) === true
      (out(1)._2 < out(2)._2) === true
      (out(0)._2 > out(2)._2) === true
    }

    "return a list of (Weight, value) for a KBooleanQuery (MUST, MUST, SHOULD)" in {
      val q = new KBooleanQuery
      q.add(mkKTextQuery("ghi"), Occur.MUST)
      q.add(mkKTextQuery("abc"), Occur.MUST)
      q.add(mkKTextQuery("def"), Occur.SHOULD)

      val w = searcher.createWeight(q).asInstanceOf[KWeight]
      val out = new ArrayBuffer[(Weight, Float)]
      w.getWeights(out)

      out.size === 3
      out.forall { case (w, v) => w.isInstanceOf[KWeight] && v > 0.0f } === true

      (out(0)._2 > out(1)._2) === true
      (out(1)._2 < out(2)._2) === true
      (out(0)._2 > out(2)._2) === true
    }

    "return a list of (Weight, value) for a KBooleanQuery (MUST, MUST_NOT, SHOULD)" in {
      val q = new KBooleanQuery
      q.add(mkKTextQuery("ghi"), Occur.MUST)
      q.add(mkKTextQuery("abc"), Occur.MUST_NOT)
      q.add(mkKTextQuery("def"), Occur.SHOULD)

      val w = searcher.createWeight(q).asInstanceOf[KWeight]
      val out = new ArrayBuffer[(Weight, Float)]
      w.getWeights(out)

      out.size === 3
      out.forall { case (w, _) => w.isInstanceOf[KWeight] } === true

      (out(0)._2 > 0.0f) === true
      (out(1)._2 == 0.0f) === true
      (out(2)._2 > 0.0f) === true

      (out(0)._2 > out(2)._2) === true
    }

    "return a list of (Weight, value) for a KBoostQuery" in {
      val q0 = new KBooleanQuery
      q0.add(mkKTextQuery("ghi"), Occur.MUST)
      q0.add(mkKTextQuery("abc"), Occur.MUST_NOT)
      q0.add(mkKTextQuery("def"), Occur.SHOULD)
      val q = new KBoostQuery(q0, mkKTextQuery("jkl"), 2.0f)

      val w = searcher.createWeight(q).asInstanceOf[KWeight]
      val out = new ArrayBuffer[(Weight, Float)]
      w.getWeights(out)

      out.size === 4
      out.forall { case (w, _) => w.isInstanceOf[KWeight] } === true

      (out(0)._2 > 0.0f) === true
      (out(1)._2 == 0.0f) === true
      (out(2)._2 > 0.0f) === true
      (out(3)._2 == 0.0f) === true

      (out(0)._2 > out(2)._2) === true
    }
  }
}

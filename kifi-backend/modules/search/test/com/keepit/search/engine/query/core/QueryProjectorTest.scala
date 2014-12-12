package com.keepit.search.engine.query.core

import com.keepit.search.query.{ FixedScoreQuery, HomePageQuery }
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.{ BooleanQuery, DisjunctionMaxQuery, PhraseQuery, TermQuery }
import org.specs2.mutable.Specification

class QueryProjectorTest extends Specification {

  import com.keepit.search.engine.query.core.QueryProjector._

  private[this] val t1 = new TermQuery(new Term("a", "t"))
  private[this] val t2 = new TermQuery(new Term("b", "t"))
  private[this] val t3 = new TermQuery(new Term("c", "t"))
  private[this] val t4 = new TermQuery(new Term("d", "t"))

  val pq = new PhraseQuery()
  pq.add(new Term("a", "t1"), 1)
  pq.add(new Term("a", "t2"), 3)
  pq.setBoost(3.14f)

  private[this] val b1 = new BooleanQuery()
  b1.add(t1, Occur.MUST)
  b1.add(t2, Occur.SHOULD)
  b1.add(t3, Occur.MUST_NOT)
  b1.setBoost(3.14f)

  private[this] val b2 = new BooleanQuery()
  b2.add(b1, Occur.SHOULD)
  b2.add(t4, Occur.SHOULD)
  b2.setBoost(2.71828f)

  private[this] val d1 = new DisjunctionMaxQuery(1.5f)
  d1.add(t1)
  d1.add(t2)
  d1.add(t3)
  d1.setBoost(3.14f)

  private[this] val d2 = new DisjunctionMaxQuery(2.5f)
  d2.add(b1)
  d2.add(t4)
  d2.setBoost(2.71828f)

  private[this] val siteQuery = new TermQuery(new Term("site", "t"))

  private[this] val textQuery = new KTextQuery("t")
  textQuery.addQuery(t1, 2.0f)
  textQuery.addQuery(t2)
  textQuery.addQuery(t3)
  textQuery.addQuery(siteQuery, 0.5f)
  textQuery.setBoost(3.14f)

  private[this] val kBoolean = new KBooleanQuery()
  kBoolean.add(b1, Occur.MUST)
  kBoolean.add(textQuery, Occur.SHOULD)
  kBoolean.setBoost(2.71828f)

  "QueryProjectorTest" should {

    "project TermQuery" in {
      project(t1, Set("a")) === t1
      project(t1, Set("b")) === null
    }

    "project PhraseQuery" in {
      project(pq, Set("a")) === pq
      project(pq, Set("b")) === null
    }

    "project BooleanQuery" in {
      project(b1, Set("a")) === {
        val expected = new BooleanQuery()
        expected.add(t1, Occur.MUST)
        expected.setBoost(3.14f)
        expected
      }
      project(b1, Set("b")) === {
        val expected = new BooleanQuery()
        expected.add(t2, Occur.SHOULD)
        expected.setBoost(3.14f)
        expected
      }
      project(b1, Set("c")) === {
        val expected = new BooleanQuery()
        expected.add(t3, Occur.MUST_NOT)
        expected.setBoost(3.14f)
        expected
      }
      project(b1, Set("a", "c")) === {
        val expected = new BooleanQuery()
        expected.add(t1, Occur.MUST)
        expected.add(t3, Occur.MUST_NOT)
        expected.setBoost(3.14f)
        expected
      }
      project(b1, Set("z")) === {
        val expected = new BooleanQuery()
        expected.setBoost(3.14f)
        expected
      }
      project(b1, Set("a", "b", "c")) === {
        b1
      }
      project(b2, Set("a", "c")) === {
        val expected = new BooleanQuery()
        expected.add(project(b1, Set("a", "c")), Occur.SHOULD)
        expected.setBoost(2.71828f)
        expected
      }
      project(b2, Set("d")) === {
        val expected = new BooleanQuery()
        expected.add(project(b1, Set("d")), Occur.SHOULD)
        expected.add(t4, Occur.SHOULD)
        expected.setBoost(2.71828f)
        expected
      }
    }

    "project DisjunctionMaxQuery" in {
      project(d1, Set("a")) === {
        val expected = new DisjunctionMaxQuery(1.5f)
        expected.add(t1)
        expected.setBoost(3.14f)
        expected
      }
      project(d1, Set("b")) === {
        val expected = new DisjunctionMaxQuery(1.5f)
        expected.add(t2)
        expected.setBoost(3.14f)
        expected
      }
      project(d1, Set("c")) === {
        val expected = new DisjunctionMaxQuery(1.5f)
        expected.add(t3)
        expected.setBoost(3.14f)
        expected
      }
      project(d1, Set("a", "c")) === {
        val expected = new DisjunctionMaxQuery(1.5f)
        expected.add(t1)
        expected.add(t3)
        expected.setBoost(3.14f)
        expected
      }
      project(d1, Set("a", "b", "c")) === {
        d1
      }
      project(d2, Set("a", "c")) === {
        val expected = new DisjunctionMaxQuery(2.5f)
        expected.add(project(b1, Set("a", "c")))
        expected.setBoost(2.71828f)
        expected
      }
      project(d2, Set("d")) === {
        val expected = new DisjunctionMaxQuery(2.5f)
        expected.add(project(b1, Set("d")))
        expected.add(t4)
        expected.setBoost(2.71828f)
        expected
      }
    }

    "do nothing for KFilterQuery" in {
      val siteQ = KSiteQuery("com")
      project(siteQ, Set("a")) === siteQ

      val mediaQ = KMediaQuery("pdf")
      project(mediaQ, Set("a")) === mediaQ

      val tagQ = KTagQuery("amazing")
      project(tagQ, Set("a")) === tagQ
    }

    "project textQuery part and do nothing for the booster part of KBoostQuery" in {
      val booster = new HomePageQuery(Seq(new Term("site", "kifi")))
      val boostQ = new KBoostQuery(b1, booster, 0.9f)
      project(boostQ, Set("a")) === {
        new KBoostQuery(project(b1, Set("a")), booster, 0.9f)
      }
    }

    "project KTextQuery" in {
      project(textQuery, Set("a", "b")) === {
        val expected = new KTextQuery("t")
        expected.addQuery(t1, 2.0f)
        expected.addQuery(t2)
        expected.setBoost(3.14f)
        expected
      }
      project(textQuery, Set("b", "c")) === {
        val expected = new KTextQuery("t")
        expected.addQuery(t2)
        expected.addQuery(t3)
        expected.setBoost(3.14f)
        expected
      }
      project(textQuery, Set("b", "site")) === {
        val expected = new KTextQuery("t")
        expected.addQuery(t2)
        expected.addQuery(siteQuery)
        expected.setBoost(3.14f)
        expected
      }
    }

    "project KBooleanQuery" in {
      project(kBoolean, Set("a", "b")) === {
        val expected = new KBooleanQuery()
        expected.add(project(b1, Set("a", "b")), Occur.MUST)
        expected.add(project(textQuery, Set("a", "b")), Occur.SHOULD)
        expected.setBoost(2.71828f)
        expected
      }
    }

    "project KWrapperQuery" in {
      val w1 = new KWrapperQuery(new FixedScoreQuery(t1))
      w1.setBoost(2.71828f)

      project(w1, Set("a")) === w1
      project(w1, Set("z")) === new KWrapperQuery(new NullQuery())

      val w2 = new KWrapperQuery(t1)
      w2.setBoost(2.71828f)

      project(w2, Set("a")) === w2
      project(w2, Set("z")) === new KWrapperQuery(new NullQuery())
    }
  }
}

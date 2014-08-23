package com.keepit.search.engine

import com.keepit.search.Lang
import com.keepit.search.engine.parser.KQueryExpansion
import com.keepit.search.engine.query.{ KBoostQuery, KBooleanQuery }
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.parser.{ DefaultSyntax, QueryParser }
import org.apache.lucene.index.Term
import org.apache.lucene.search.TermQuery
import org.specs2.mutable.Specification

class QueryEngineBuilderTest extends Specification {

  def getParser(): QueryParser = {
    val english = Lang("en")
    val analyzer = DefaultAnalyzer.getAnalyzer(english)
    val stemmingAnalyzer = DefaultAnalyzer.getAnalyzerWithStemmer(english)
    new QueryParser(analyzer, stemmingAnalyzer) with DefaultSyntax with KQueryExpansion {
      override val lang = english
      override val altAnalyzer = None
      override val altStemmingAnalyzer = None
      override val siteBoost: Float = 1.0f
      override val concatBoost: Float = 0.5f
    }
  }

  private[this] val tb = QueryEngineBuilder.tieBreakerMultiplier

  "QueryEngineBuilder" should {

    "build an engine from a parsed query with a single term" in {
      val query = getParser().parse("information").get
      query must beAnInstanceOf[KBooleanQuery]

      val builder = new QueryEngineBuilder(query)
      builder.build().getScoreExpr().toString() === s"MaxWithTieBreaker(0, $tb)"
    }

    "build an engine from a parsed query with a phrase" in {
      val query = getParser().parse("taming \"information overload\"").get
      query must beAnInstanceOf[KBooleanQuery]

      val builder = new QueryEngineBuilder(query)
      builder.build().getScoreExpr().toString() === s"DisjunctiveSum(MaxWithTieBreaker(0, $tb), MaxWithTieBreaker(1, $tb))"
    }

    "build an engine from a parsed query with multiple terms" in {
      val query = getParser().parse("taming information overload").get
      query must beAnInstanceOf[KBooleanQuery]

      val builder = new QueryEngineBuilder(query)
      builder.build().getScoreExpr().toString() === s"DisjunctiveSum(MaxWithTieBreaker(0, $tb), MaxWithTieBreaker(1, $tb), MaxWithTieBreaker(2, $tb))"
    }

    "build an engine from a parsed query with optional and required" in {
      val query = getParser().parse("taming +information +overload together").get
      query must beAnInstanceOf[KBooleanQuery]

      val builder = new QueryEngineBuilder(query)
      builder.build().getScoreExpr().toString() ===
        s"Boolean(DisjunctiveSum(MaxWithTieBreaker(0, $tb), MaxWithTieBreaker(3, $tb)), ConjunctiveSum(MaxWithTieBreaker(1, $tb), MaxWithTieBreaker(2, $tb)))"
    }

    "build an engine from a parsed query with optional, required and prohibited" in {
      val query = getParser().parse("taming +information +overload together -bookmark").get
      query must beAnInstanceOf[KBooleanQuery]

      val builder = new QueryEngineBuilder(query)
      builder.build().getScoreExpr().toString() ===
        s"FilterOut(Boolean(DisjunctiveSum(MaxWithTieBreaker(0, $tb), MaxWithTieBreaker(3, $tb)), ConjunctiveSum(MaxWithTieBreaker(1, $tb), MaxWithTieBreaker(2, $tb))), Max(4))"
    }

    "build an engine from a parsed query with optional, required and more prohibited" in {
      val query = getParser().parse("taming +information +overload together -bookmark -chat").get
      query must beAnInstanceOf[KBooleanQuery]

      val builder = new QueryEngineBuilder(query)
      builder.build().getScoreExpr().toString() ===
        s"FilterOut(Boolean(DisjunctiveSum(MaxWithTieBreaker(0, $tb), MaxWithTieBreaker(3, $tb)), ConjunctiveSum(MaxWithTieBreaker(1, $tb), MaxWithTieBreaker(2, $tb))), Exists(Max(4), Max(5)))"
    }

    "build an engine from a parsed query with optional, required and more prohibited" in {
      val query = getParser().parse("taming +information +overload together -bookmark -chat").get
      query must beAnInstanceOf[KBooleanQuery]

      val builder = new QueryEngineBuilder(query)
      builder.build().getScoreExpr().toString() ===
        s"FilterOut(Boolean(DisjunctiveSum(MaxWithTieBreaker(0, $tb), MaxWithTieBreaker(3, $tb)), ConjunctiveSum(MaxWithTieBreaker(1, $tb), MaxWithTieBreaker(2, $tb))), Exists(Max(4), Max(5)))"
    }

    "build an engine from a parsed query with booster" in {
      val query = getParser().parse("information overload").get
      query must beAnInstanceOf[KBooleanQuery]

      val builder = new QueryEngineBuilder(query)
      builder.addBoosterQuery(new TermQuery(new Term("", "important")), 2.0f)
      val engine = builder.build()
      engine.getScoreExpr().toString() ===
        s"Boost(DisjunctiveSum(MaxWithTieBreaker(0, $tb), MaxWithTieBreaker(1, $tb)), Max(2), 2.0)"
      engine.getQuery() must beAnInstanceOf[KBoostQuery]
    }
  }
}

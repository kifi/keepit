package com.keepit.search.engine.parser

import org.apache.lucene.search.BooleanClause._
import org.apache.lucene.search.{ BooleanQuery, Query }
import org.specs2.mutable._
import org.specs2.specification.Scope

class QueryParserTest extends Specification {

  private trait QueryParserScope extends Scope {
    val analyzer = new org.apache.lucene.analysis.standard.StandardAnalyzer()
    val parser = new QueryParser(analyzer, analyzer) with DefaultSyntax
  }

  "QueryParser" should {
    "be forgiving to lucene parser error" in new QueryParserScope {
      parser.parse("aaa\"bbb") must beSome[Query]
      parser.parse("aaa \"bbb") must beSome[Query]
      parser.parse("aaa (bbb") must beSome[Query]
      parser.parse("aaa (bbb))") must beSome[Query]
      parser.parse("aaa) bbb") must beSome[Query]
      parser.parse("+") must beNone
      parser.parse("\"") must beNone
    }

    "handle an empty query" in new QueryParserScope {
      parser.parse(" ") must beNone
      parser.parse("") must beNone
      parser.parse(null) must beNone
    }

    "handle query operators" in new QueryParserScope {
      var query = parser.parse("+aaa")
      query must beSome[Query]

      query = parser.parse("+aaa +bbb")
      query must beSome[Query]

      var clauses = query.get.asInstanceOf[BooleanQuery].getClauses
      clauses.length === 2
      clauses(0).getOccur() === Occur.MUST
      clauses(1).getOccur() === Occur.MUST

      query = parser.parse("aaa +bbb")
      query must beSome[Query]

      clauses = query.get.asInstanceOf[BooleanQuery].getClauses
      clauses.length === 2
      clauses(0).getOccur() === Occur.SHOULD
      clauses(1).getOccur() === Occur.MUST

      query = parser.parse("-aaa bbb")
      query must beSome[Query]

      clauses = query.get.asInstanceOf[BooleanQuery].getClauses
      clauses.length === 2
      clauses(0).getOccur() === Occur.MUST_NOT
      clauses(1).getOccur() === Occur.SHOULD

      query = parser.parse("aaa -bbb")
      query must beSome[Query]

      clauses = query.get.asInstanceOf[BooleanQuery].getClauses
      clauses.length === 2
      clauses(0).getOccur() === Occur.SHOULD
      clauses(1).getOccur() === Occur.MUST_NOT

      query = parser.parse("-aaa +bbb")
      query must beSome[Query]

      clauses = query.get.asInstanceOf[BooleanQuery].getClauses
      clauses.length === 2
      clauses(0).getOccur() === Occur.MUST_NOT
      clauses(1).getOccur() === Occur.MUST

      query = parser.parse("+aaa -bbb")
      query must beSome[Query]

      clauses = query.get.asInstanceOf[BooleanQuery].getClauses
      clauses.length === 2
      clauses(0).getOccur() === Occur.MUST
      clauses(1).getOccur() === Occur.MUST_NOT

    }

    "handle double quotes" in new QueryParserScope {
      var query = parser.parse("\"aaa\"")
      query.toString === "Some(aaa)"

      query = parser.parse("\"aaa\" ")
      query.toString === "Some(aaa)"

      query = parser.parse("\"aaa bbb\"")
      query.toString === "Some(\"aaa bbb\")"

      query = parser.parse("\"aaa bbb\" ")
      query.toString === "Some(\"aaa bbb\")"

      query = parser.parse("\"aaa\"bbb\"")
      query.toString === "Some(\"aaa bbb\")"

      query = parser.parse("\"aaa\"bbb\" ")
      query.toString === "Some(\"aaa bbb\")"

      query = parser.parse("aaa\"bbb")
      query.toString === "Some(\"aaa bbb\")"

      query = parser.parse("aaa\"bbb ")
      query.toString === "Some(\"aaa bbb\")"

      query = parser.parse("\"aaa bbb\"  ")
      query.toString === "Some(\"aaa bbb\")"
    }

    "handle missing term after operators" in new QueryParserScope {
      var query = parser.parse("+")
      query.toString === "None"

      query = parser.parse("aaa +")
      query.toString === "Some(aaa)"
      query = parser.parse("aaa -")
      query.toString === "Some(aaa)"

      query = parser.parse("aaa site:")
      query.toString === "Some(aaa)"
    }

    "strip leading spaces" in new QueryParserScope {
      var query = parser.parse("    aaa")
      query.toString === "Some(aaa)"
    }

    "detect trailing terms" in new QueryParserScope {
      parser.parseSpecs("    aaa bbb").get.head === QuerySpec(Occur.SHOULD, "", "aaa", false, false)
      parser.parseSpecs("    aaa ").get.head === QuerySpec(Occur.SHOULD, "", "aaa", false, false)
      parser.parseSpecs("    \"aaa\" ").get.head === QuerySpec(Occur.SHOULD, "", "aaa", true, false)

      parser.parseSpecs("    aaa").get.head === QuerySpec(Occur.SHOULD, "", "aaa", false, true)
      parser.parseSpecs("    \"aaa\"").get.head === QuerySpec(Occur.SHOULD, "", "aaa", true, true)
    }
  }
}

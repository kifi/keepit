package com.keepit.search.query.parser

import com.keepit.search.Lang
import org.specs2.specification.Scope
import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import com.keepit.search.graph.UserToUserEdgeSet
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanClause._
import org.apache.lucene.search.BooleanClause._
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.Query
import com.keepit.search.index.DefaultAnalyzer

class QueryParserTest extends Specification {

  private trait QueryParserScope extends Scope {
    val analyzer = DefaultAnalyzer.forParsing(Lang("en"))
    val parser = new QueryParser(analyzer, None) with DefaultSyntax {
      override val fields = Set("", "site")
    }
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
    }
  }
}

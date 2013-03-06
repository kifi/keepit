package com.keepit.search.index

import com.keepit.search.Lang
import org.apache.lucene.search.BooleanClause._
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.Query
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import org.specs2.specification.Scope

@RunWith(classOf[JUnitRunner])
class QueryParserTest extends SpecificationWithJUnit {

  private trait QueryParserScope extends Scope {
    val analyzer = DefaultAnalyzer.forParsing(Lang("en"))
    val parser = new QueryParser(analyzer) {
      override def parseQuery(queryText: String) = {
        val qopt = super.parseQuery(queryText)
        //println("[%s]=>[%s]".format(queryText, qopt))
        qopt
      }
    }
  }

  "QueryParser" should {
    "be forgiving to lucene parser error" in new QueryParserScope {
      parser.parseQuery("aaa\"bbb") must beSome[Query]
      parser.parseQuery("aaa \"bbb") must beSome[Query]
      parser.parseQuery("aaa (bbb") must beSome[Query]
      parser.parseQuery("aaa (bbb))") must beSome[Query]
      parser.parseQuery("aaa) bbb") must beSome[Query]
      parser.parseQuery("+") must beSome[Query]
      parser.parseQuery("\"") must beSome[Query]
    }

    "handle an empty query" in new QueryParserScope {
      parser.parseQuery(" ") must beNone
      parser.parseQuery("") must beNone
      parser.parseQuery(null) must beNone
    }

    "handle query operators" in new QueryParserScope {
      var query = parser.parseQuery("+aaa")
      query must beSome[Query]

      query = parser.parseQuery("+aaa +bbb")
      query must beSome[Query]

      var clauses = query.get.asInstanceOf[BooleanQuery].getClauses
      clauses.length === 2
      clauses(0).getOccur() === Occur.MUST
      clauses(1).getOccur() === Occur.MUST

      query = parser.parseQuery("aaa +bbb")
      query must beSome[Query]

      clauses = query.get.asInstanceOf[BooleanQuery].getClauses
      clauses.length === 2
      clauses(0).getOccur() === Occur.SHOULD
      clauses(1).getOccur() === Occur.MUST
    }
  }
}

package com.keepit.search.index

import com.keepit.test.EmptyApplication
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import com.keepit.search.graph.UserToUserEdgeSet
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import org.apache.lucene.search.Query

@RunWith(classOf[JUnitRunner])
class QueryParserTest extends SpecificationWithJUnit {

  val analyzer = new DefaultAnalyzer
  val config = new IndexWriterConfig(Version.LUCENE_36, analyzer)
  val parser = new QueryParser(config) {
    override def parseQuery(queryText: String) = {
      val qopt = super.parseQuery(queryText)
      //println("[%s]=>[%s]".format(queryText, qopt))
      qopt
    }
  }

  "QueryParser" should {
    "be forgiving to lucene parser error" in {
      parser.parseQuery("aaa\"bbb") must beSome[Query]
      parser.parseQuery("aaa \"bbb") must beSome[Query]
      parser.parseQuery("aaa (bbb") must beSome[Query]
      parser.parseQuery("aaa (bbb))") must beSome[Query]
      parser.parseQuery("aaa) bbb") must beSome[Query]
    }
  }
}

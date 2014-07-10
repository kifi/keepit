package com.keepit.search.query.parser

import com.keepit.search.Lang
import org.specs2.specification.Scope
import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import org.apache.lucene.analysis.core.WhitespaceAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanClause._
import org.apache.lucene.search.BooleanClause._
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.Query
import org.apache.lucene.store.RAMDirectory
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.TextQuery

class QueryExpansionTest extends Specification {

  private class QueryParserScope(concatBoostValue: Float) extends Scope {
    val english = Lang("en")
    val analyzer = DefaultAnalyzer.getAnalyzer(english)
    val stemmingAnalyzer = DefaultAnalyzer.getAnalyzerWithStemmer(english)
    val parser = new QueryParser(analyzer, stemmingAnalyzer) with DefaultSyntax with QueryExpansion {
      override val lang = english
      override val altAnalyzer = None
      override val altStemmingAnalyzer = None
      override val siteBoost: Float = 1.0f
      override val concatBoost: Float = concatBoostValue
    }
  }

  private[this] val p = TextQuery.personalQueryTieBreakerMultiplier
  private[this] val r = TextQuery.regularQueryTieBreakerMultiplier

  "QueryExpansion" should {
    "expand queries (with no concat)" in new QueryParserScope(concatBoostValue = 0.0f) {
      parser.parse("super conductivity").get.toString ===
        s"TextQuery((title:super | title_stemmed:super)~$p (t:super | c:super | ts:super | cs:super)~$r ()) " +
        s"TextQuery((title:conductivity | title_stemmed:conductivity)~$p (t:conductivity | c:conductivity | ts:conductivity | cs:conductivity)~$r ())"

      parser.parse("Electromagnetically induced transparency").get.toString ===
        s"TextQuery((title:electromagnetically | title_stemmed:electromagnet)~$p (t:electromagnetically | c:electromagnetically | ts:electromagnet | cs:electromagnet)~$r ()) " +
        s"TextQuery((title:induced | title_stemmed:induce)~$p (t:induced | c:induced | ts:induce | cs:induce)~$r ()) " +
        s"TextQuery((title:transparency | title_stemmed:transparency)~$p (t:transparency | c:transparency | ts:transparency | cs:transparency)~$r ())"

      parser.parse("bose-einstein condensate").get.toString ===
        "TextQuery(" +
        s"""(title:"bose einstein" | title_stemmed:"bose einstein")~$p """ +
        s"""(t:"bose einstein" | c:"bose einstein" | ts:"bose einstein" | cs:"bose einstein")~$r """ +
        "()) " +
        "TextQuery(" +
        s"""(title:condensate | title_stemmed:condensate)~$p (t:condensate | c:condensate | ts:condensate | cs:condensate)~$r """ +
        "())"

      parser.parse("\"Spin-statistics\" theorem").get.toString ===
        "TextQuery(" +
        s"""(title:"spin statistics")~$p (t:"spin statistics" | c:"spin statistics")~$r """ +
        "()) " +
        "TextQuery(" +
        s"""(title:theorem | title_stemmed:theorem)~$p (t:theorem | c:theorem | ts:theorem | cs:theorem)~$r """ +
        "())"
    }

    "expand queries (with concat)" in new QueryParserScope(concatBoostValue = 0.5f) {
      parser.parse("super conductivity").get.toString ===
        "TextQuery(" +
        s"(title:super | title_stemmed:super |" +
        s" title:superconductivity^0.5 | title_stemmed:superconductivity^0.5)~$p " +
        s"(t:super | c:super | ts:super | cs:super |" +
        s" t:superconductivity^0.5 | c:superconductivity^0.5 | ts:superconductivity^0.5 | cs:superconductivity^0.5)~$r " +
        "()) " +
        "TextQuery(" +
        s"(title:conductivity | title_stemmed:conductivity |" +
        s" title:superconductivity^0.5 | title_stemmed:superconductivity^0.5)~$p " +
        s"(t:conductivity | c:conductivity | ts:conductivity | cs:conductivity |" +
        s" t:superconductivity^0.5 | c:superconductivity^0.5 | ts:superconductivity^0.5 | cs:superconductivity^0.5)~$r " +
        "())"

      parser.parse("Electromagnetically induced transparency").get.toString ===
        "TextQuery(" +
        s"(title:electromagnetically | title_stemmed:electromagnet | title:electromagneticallyinduced^0.5 | title_stemmed:electromagneticallyinduce^0.5)~$p " +
        s"(t:electromagnetically | c:electromagnetically | ts:electromagnet | cs:electromagnet |" +
        s" t:electromagneticallyinduced^0.5 | c:electromagneticallyinduced^0.5 |" +
        s" ts:electromagneticallyinduce^0.5 | cs:electromagneticallyinduce^0.5)~$r " +
        "()) " +
        "TextQuery(" +
        s"(title:induced | title_stemmed:induce |" +
        s" title:electromagneticallyinduced^0.5 | title_stemmed:electromagneticallyinduce^0.5 | title:inducedtransparency^0.5 | title_stemmed:inducedtransparency^0.5)~$p " +
        s"(t:induced | c:induced | ts:induce | cs:induce |" +
        s" t:electromagneticallyinduced^0.5 | c:electromagneticallyinduced^0.5 | ts:electromagneticallyinduce^0.5 | cs:electromagneticallyinduce^0.5 |" +
        s" t:inducedtransparency^0.5 | c:inducedtransparency^0.5 | ts:inducedtransparency^0.5 | cs:inducedtransparency^0.5)~$r " +
        "()) " +
        "TextQuery(" +
        s"(title:transparency | title_stemmed:transparency | title:inducedtransparency^0.5 | title_stemmed:inducedtransparency^0.5)~$p " +
        s"(t:transparency | c:transparency | ts:transparency | cs:transparency |" +
        s" t:inducedtransparency^0.5 | c:inducedtransparency^0.5 | ts:inducedtransparency^0.5 | cs:inducedtransparency^0.5)~$r " +
        "())"

      parser.parse("bose-einstein condensate").get.toString ===
        "TextQuery(" +
        s"""(title:"bose einstein" | title_stemmed:"bose einstein" |""" +
        s""" title:boseeinstein^0.5 | title_stemmed:boseeinstein^0.5 | title:boseeinsteincondensate^0.5 | title_stemmed:boseeinsteincondensate^0.5)~$p """ +
        s"""(t:"bose einstein" | c:"bose einstein" | ts:"bose einstein" | cs:"bose einstein" |""" +
        s""" t:boseeinstein^0.5 | c:boseeinstein^0.5 | ts:boseeinstein^0.5 | cs:boseeinstein^0.5 |""" +
        s""" t:boseeinsteincondensate^0.5 | c:boseeinsteincondensate^0.5 | ts:boseeinsteincondensate^0.5 | cs:boseeinsteincondensate^0.5)~$r """ +
        "()) " +
        "TextQuery(" +
        s"""(title:condensate | title_stemmed:condensate | title:boseeinsteincondensate^0.5 | title_stemmed:boseeinsteincondensate^0.5)~$p """ +
        s"""(t:condensate | c:condensate | ts:condensate | cs:condensate |""" +
        s""" t:boseeinsteincondensate^0.5 | c:boseeinsteincondensate^0.5 | ts:boseeinsteincondensate^0.5 | cs:boseeinsteincondensate^0.5)~$r """ +
        "())"

      parser.parse("\"Spin-statistics\" theorem").get.toString ===
        "TextQuery(" +
        s"""(title:"spin statistics")~$p """ +
        s"""(t:"spin statistics" | c:"spin statistics")~$r """ +
        "()) " +
        s"TextQuery(" +
        s"""(title:theorem | title_stemmed:theorem)~$p """ +
        s"""(t:theorem | c:theorem | ts:theorem | cs:theorem)~$r """ +
        "())"
    }

    "expand a query with site" in new QueryParserScope(concatBoostValue = 0.0f) {
      parser.parse("www.yahoo.com").get.toString ===
        "TextQuery(" +
        s"""(title:"www yahoo com" | title_stemmed:"www yahoo com")~$p """ +
        s"""(t:"www yahoo com" | c:"www yahoo com" | site(site:www.yahoo.com) | ts:"www yahoo com" | cs:"www yahoo com")~$r """ +
        "())"
    }
  }
}

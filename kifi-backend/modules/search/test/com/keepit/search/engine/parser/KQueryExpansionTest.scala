package com.keepit.search.engine.parser

import com.keepit.search.Lang
import com.keepit.search.engine.query.{ KBooleanQuery, KTextQuery }
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.parser.DefaultSyntax
import com.keepit.search.query.parser.QueryParser
import org.specs2.specification.Scope
import org.specs2.mutable._

class KQueryExpansionTest extends Specification {

  private class QueryParserScope(concatBoostValue: Float) extends Scope {
    val english = Lang("en")
    val analyzer = DefaultAnalyzer.getAnalyzer(english)
    val stemmingAnalyzer = DefaultAnalyzer.getAnalyzerWithStemmer(english)
    val parser = new QueryParser(analyzer, stemmingAnalyzer) with DefaultSyntax with KQueryExpansion {
      override val lang = english
      override val altAnalyzer = None
      override val altStemmingAnalyzer = None
      override val siteBoost: Float = 1.0f
      override val concatBoost: Float = concatBoostValue
    }
  }

  private[this] val tb = KTextQuery.tieBreakerMultiplier

  "KQueryExpansion" should {
    "expand queries (with no concat)" in new QueryParserScope(concatBoostValue = 0.0f) {
      var query = parser.parse("super conductivity").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        s"KTextQuery((t:super | c:super | h:super | ts:super | cs:super | hs:super)~$tb) " +
        s"KTextQuery((t:conductivity | c:conductivity | h:conductivity | ts:conductivity | cs:conductivity | hs:conductivity)~$tb)"

      query = parser.parse("Electromagnetically induced transparency").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        s"KTextQuery((t:electromagnetically | c:electromagnetically | h:electromagnetically | ts:electromagnet | cs:electromagnet | hs:electromagnet)~$tb) " +
        s"KTextQuery((t:induced | c:induced | h:induced | ts:induce | cs:induce | hs:induce)~$tb) " +
        s"KTextQuery((t:transparency | c:transparency | h:transparency | ts:transparency | cs:transparency | hs:transparency)~$tb)"

      query = parser.parse("bose-einstein condensate").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        s"""KTextQuery((t:"bose einstein" | c:"bose einstein" | h:"bose einstein" | ts:"bose einstein" | cs:"bose einstein" | hs:"bose einstein")~$tb) """ +
        s"""KTextQuery((t:condensate | c:condensate | h:condensate | ts:condensate | cs:condensate | hs:condensate)~$tb)"""

      query = parser.parse("\"Spin-statistics\" theorem").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        s"""KTextQuery((t:"spin statistics" | c:"spin statistics" | h:"spin statistics")~$tb) """ +
        s"""KTextQuery((t:theorem | c:theorem | h:theorem | ts:theorem | cs:theorem | hs:theorem)~$tb)"""
    }

    "expand queries (with concat)" in new QueryParserScope(concatBoostValue = 0.5f) {
      var query = parser.parse("super conductivity").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        "KTextQuery(" +
        s"(t:super | c:super | h:super | ts:super | cs:super | hs:super |" +
        s" t:superconductivity^0.5 | c:superconductivity^0.5 | h:superconductivity^0.5 | ts:superconductivity^0.5 | cs:superconductivity^0.5 | hs:superconductivity^0.5)~$tb) " +
        "KTextQuery(" +
        s"(t:conductivity | c:conductivity | h:conductivity | ts:conductivity | cs:conductivity | hs:conductivity |" +
        s" t:superconductivity^0.5 | c:superconductivity^0.5 | h:superconductivity^0.5 | ts:superconductivity^0.5 | cs:superconductivity^0.5 | hs:superconductivity^0.5)~$tb)"

      query = parser.parse("Electromagnetically induced transparency").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        "KTextQuery(" +
        s"(t:electromagnetically | c:electromagnetically | h:electromagnetically | ts:electromagnet | cs:electromagnet | hs:electromagnet |" +
        s" t:electromagneticallyinduced^0.5 | c:electromagneticallyinduced^0.5 | h:electromagneticallyinduced^0.5 |" +
        s" ts:electromagneticallyinduce^0.5 | cs:electromagneticallyinduce^0.5 | hs:electromagneticallyinduce^0.5)~$tb) " +
        "KTextQuery(" +
        s"(t:induced | c:induced | h:induced | ts:induce | cs:induce | hs:induce |" +
        s" t:electromagneticallyinduced^0.5 | c:electromagneticallyinduced^0.5 | h:electromagneticallyinduced^0.5 | ts:electromagneticallyinduce^0.5 | cs:electromagneticallyinduce^0.5 | hs:electromagneticallyinduce^0.5 |" +
        s" t:inducedtransparency^0.5 | c:inducedtransparency^0.5 | h:inducedtransparency^0.5 | ts:inducedtransparency^0.5 | cs:inducedtransparency^0.5 | hs:inducedtransparency^0.5)~$tb) " +
        "KTextQuery(" +
        s"(t:transparency | c:transparency | h:transparency | ts:transparency | cs:transparency | hs:transparency |" +
        s" t:inducedtransparency^0.5 | c:inducedtransparency^0.5 | h:inducedtransparency^0.5 | ts:inducedtransparency^0.5 | cs:inducedtransparency^0.5 | hs:inducedtransparency^0.5)~$tb)"

      query = parser.parse("bose-einstein condensate").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        "KTextQuery(" +
        s"""(t:"bose einstein" | c:"bose einstein" | h:"bose einstein" | ts:"bose einstein" | cs:"bose einstein" | hs:"bose einstein" |""" +
        s""" t:boseeinstein^0.5 | c:boseeinstein^0.5 | h:boseeinstein^0.5 | ts:boseeinstein^0.5 | cs:boseeinstein^0.5 | hs:boseeinstein^0.5 |""" +
        s""" t:boseeinsteincondensate^0.5 | c:boseeinsteincondensate^0.5 | h:boseeinsteincondensate^0.5 | ts:boseeinsteincondensate^0.5 | cs:boseeinsteincondensate^0.5 | hs:boseeinsteincondensate^0.5)~$tb) """ +
        "KTextQuery(" +
        s"""(t:condensate | c:condensate | h:condensate | ts:condensate | cs:condensate | hs:condensate |""" +
        s""" t:boseeinsteincondensate^0.5 | c:boseeinsteincondensate^0.5 | h:boseeinsteincondensate^0.5 | ts:boseeinsteincondensate^0.5 | cs:boseeinsteincondensate^0.5 | hs:boseeinsteincondensate^0.5)~$tb)"""

      query = parser.parse("\"Spin-statistics\" theorem").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        s"""KTextQuery((t:"spin statistics" | c:"spin statistics" | h:"spin statistics")~$tb) """ +
        s"""KTextQuery((t:theorem | c:theorem | h:theorem | ts:theorem | cs:theorem | hs:theorem)~$tb)"""
    }

    "expand a query with site" in new QueryParserScope(concatBoostValue = 0.0f) {
      parser.parse("www.yahoo.com").get.toString ===
        s"""KTextQuery((t:"www yahoo com" | c:"www yahoo com" | h:"www yahoo com" | site(site:www.yahoo.com) | ts:"www yahoo com" | cs:"www yahoo com" | hs:"www yahoo com")~$tb)"""
    }
  }
}

package com.keepit.search.engine.parser

import com.keepit.search.Lang
import com.keepit.search.engine.query.KTextQuery
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
      parser.parse("super conductivity").get.toString ===
        s"KTextQuery((t:super | c:super | ts:super | cs:super)~$tb ()) " +
        s"KTextQuery((t:conductivity | c:conductivity | ts:conductivity | cs:conductivity)~$tb ())"

      parser.parse("Electromagnetically induced transparency").get.toString ===
        s"KTextQuery((t:electromagnetically | c:electromagnetically | ts:electromagnet | cs:electromagnet)~$tb ()) " +
        s"KTextQuery((t:induced | c:induced | ts:induce | cs:induce)~$tb ()) " +
        s"KTextQuery((t:transparency | c:transparency | ts:transparency | cs:transparency)~$tb ())"

      parser.parse("bose-einstein condensate").get.toString ===
        "KTextQuery(" +
        s"""(t:"bose einstein" | c:"bose einstein" | ts:"bose einstein" | cs:"bose einstein")~$tb """ +
        "()) " +
        "KTextQuery(" +
        s"""(t:condensate | c:condensate | ts:condensate | cs:condensate)~$tb """ +
        "())"

      parser.parse("\"Spin-statistics\" theorem").get.toString ===
        "KTextQuery(" +
        s"""(t:"spin statistics" | c:"spin statistics")~$tb """ +
        "()) " +
        "KTextQuery(" +
        s"""(t:theorem | c:theorem | ts:theorem | cs:theorem)~$tb """ +
        "())"
    }

    "expand queries (with concat)" in new QueryParserScope(concatBoostValue = 0.5f) {
      parser.parse("super conductivity").get.toString ===
        "KTextQuery(" +
        s"(t:super | c:super | ts:super | cs:super |" +
        s" t:superconductivity^0.5 | c:superconductivity^0.5 | ts:superconductivity^0.5 | cs:superconductivity^0.5)~$tb " +
        "()) " +
        "KTextQuery(" +
        s"(t:conductivity | c:conductivity | ts:conductivity | cs:conductivity |" +
        s" t:superconductivity^0.5 | c:superconductivity^0.5 | ts:superconductivity^0.5 | cs:superconductivity^0.5)~$tb " +
        "())"

      parser.parse("Electromagnetically induced transparency").get.toString ===
        "KTextQuery(" +
        s"(t:electromagnetically | c:electromagnetically | ts:electromagnet | cs:electromagnet |" +
        s" t:electromagneticallyinduced^0.5 | c:electromagneticallyinduced^0.5 |" +
        s" ts:electromagneticallyinduce^0.5 | cs:electromagneticallyinduce^0.5)~$tb " +
        "()) " +
        "KTextQuery(" +
        s"(t:induced | c:induced | ts:induce | cs:induce |" +
        s" t:electromagneticallyinduced^0.5 | c:electromagneticallyinduced^0.5 | ts:electromagneticallyinduce^0.5 | cs:electromagneticallyinduce^0.5 |" +
        s" t:inducedtransparency^0.5 | c:inducedtransparency^0.5 | ts:inducedtransparency^0.5 | cs:inducedtransparency^0.5)~$tb " +
        "()) " +
        "KTextQuery(" +
        s"(t:transparency | c:transparency | ts:transparency | cs:transparency |" +
        s" t:inducedtransparency^0.5 | c:inducedtransparency^0.5 | ts:inducedtransparency^0.5 | cs:inducedtransparency^0.5)~$tb " +
        "())"

      parser.parse("bose-einstein condensate").get.toString ===
        "KTextQuery(" +
        s"""(t:"bose einstein" | c:"bose einstein" | ts:"bose einstein" | cs:"bose einstein" |""" +
        s""" t:boseeinstein^0.5 | c:boseeinstein^0.5 | ts:boseeinstein^0.5 | cs:boseeinstein^0.5 |""" +
        s""" t:boseeinsteincondensate^0.5 | c:boseeinsteincondensate^0.5 | ts:boseeinsteincondensate^0.5 | cs:boseeinsteincondensate^0.5)~$tb """ +
        "()) " +
        "KTextQuery(" +
        s"""(t:condensate | c:condensate | ts:condensate | cs:condensate |""" +
        s""" t:boseeinsteincondensate^0.5 | c:boseeinsteincondensate^0.5 | ts:boseeinsteincondensate^0.5 | cs:boseeinsteincondensate^0.5)~$tb """ +
        "())"

      parser.parse("\"Spin-statistics\" theorem").get.toString ===
        "KTextQuery(" +
        s"""(t:"spin statistics" | c:"spin statistics")~$tb """ +
        "()) " +
        s"KTextQuery(" +
        s"""(t:theorem | c:theorem | ts:theorem | cs:theorem)~$tb """ +
        "())"
    }

    "expand a query with site" in new QueryParserScope(concatBoostValue = 0.0f) {
      parser.parse("www.yahoo.com").get.toString ===
        "KTextQuery(" +
        s"""(t:"www yahoo com" | c:"www yahoo com" | site(site:www.yahoo.com) | ts:"www yahoo com" | cs:"www yahoo com")~$tb """ +
        "())"
    }
  }
}

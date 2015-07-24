package com.keepit.search.engine.parser

import com.keepit.search.Lang
import com.keepit.search.engine.query.core.{ KTextQuery, KBooleanQuery }
import com.keepit.search.index.DefaultAnalyzer
import org.specs2.specification.Scope
import org.specs2.mutable._

class KQueryExpansionTest extends Specification {

  private class QueryParserScope(concatBoostValue: Float, trailingPrefixBoost: Float, prefixBoost: Float) extends Scope {
    val english = Lang("en")
    val analyzer = DefaultAnalyzer.getAnalyzer(english)
    val stemmingAnalyzer = DefaultAnalyzer.getAnalyzerWithStemmer(english)
    implicit val parser = new QueryParser(analyzer, stemmingAnalyzer) with DefaultSyntax with KQueryExpansion {
      override val lang = english
      override val altAnalyzer = None
      override val altStemmingAnalyzer = None
      val titleBoost: Float = 2.0f
      val siteBoost: Float = 1.0f
      val concatBoost: Float = concatBoostValue
      def getPrefixBoost(trailing: Boolean): Float = if (trailing) trailingPrefixBoost else prefixBoost
    }
  }

  private[this] val tb = KTextQuery.tieBreakerMultiplier
  private[this] def b(implicit queryExpansion: KQueryExpansion) = if (queryExpansion.titleBoost == 1.0f) "" else s"^${queryExpansion.titleBoost}"

  "KQueryExpansion" should {
    "expand queries (with no concat, with prefix search on trailing term)" in new QueryParserScope(concatBoostValue = 0.0f, trailingPrefixBoost = 1.0f, prefixBoost = 0.0f) {
      var query = parser.parse("super conductivity").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        s"KTextQuery((t:super$b | c:super | h:super | ts:super$b | cs:super | hs:super)~$tb) " +
        s"KTextQuery((t:conductivity$b | c:conductivity | h:conductivity | ts:conductivity$b | cs:conductivity | hs:conductivity | KPrefixQuery(tp-tv: conductivity))~$tb)"

      query = parser.parse("Electromagnetically induced transparency").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        s"KTextQuery((t:electromagnetically$b | c:electromagnetically | h:electromagnetically | ts:electromagnet$b | cs:electromagnet | hs:electromagnet)~$tb) " +
        s"KTextQuery((t:induced$b | c:induced | h:induced | ts:induce$b | cs:induce | hs:induce)~$tb) " +
        s"KTextQuery((t:transparency$b | c:transparency | h:transparency | ts:transparency$b | cs:transparency | hs:transparency | KPrefixQuery(tp-tv: transparency))~$tb)"

      query = parser.parse("bose-einstein condensate").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        s"""KTextQuery((t:"bose einstein"$b | c:"bose einstein" | h:"bose einstein" | ts:"bose einstein"$b | cs:"bose einstein" | hs:"bose einstein")~$tb) """ +
        s"""KTextQuery((t:condensate$b | c:condensate | h:condensate | ts:condensate$b | cs:condensate | hs:condensate | KPrefixQuery(tp-tv: condensate))~$tb)"""

      query = parser.parse("bose-einstein condensate ").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        s"""KTextQuery((t:"bose einstein"$b | c:"bose einstein" | h:"bose einstein" | ts:"bose einstein"$b | cs:"bose einstein" | hs:"bose einstein")~$tb) """ +
        s"""KTextQuery((t:condensate$b | c:condensate | h:condensate | ts:condensate$b | cs:condensate | hs:condensate)~$tb)"""

      query = parser.parse("bose-einstein").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        s"""KTextQuery((t:"bose einstein"$b | c:"bose einstein" | h:"bose einstein" | ts:"bose einstein"$b | cs:"bose einstein" | hs:"bose einstein" | KPrefixQuery(tp-tv: bose-einstein))~$tb)"""

      query = parser.parse("\"Spin-statistics\" theorem").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        s"""KTextQuery((t:"spin statistics"$b | c:"spin statistics" | h:"spin statistics")~$tb) """ +
        s"""KTextQuery((t:theorem$b | c:theorem | h:theorem | ts:theorem$b | cs:theorem | hs:theorem | KPrefixQuery(tp-tv: theorem))~$tb)"""

      query = parser.parse("\"Spin-statistics\"").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        s"""KTextQuery((t:"spin statistics"$b | c:"spin statistics" | h:"spin statistics")~$tb)"""
    }

    "expand queries (with concat, with prefix search on all terms)" in new QueryParserScope(concatBoostValue = 0.5f, trailingPrefixBoost = 1.0f, prefixBoost = 1.0f) {
      var query = parser.parse("super conductivity").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        "KTextQuery(" +
        s"(t:super$b | c:super | h:super | ts:super$b | cs:super | hs:super | KPrefixQuery(tp-tv: super) |" +
        s" t:superconductivity^0.5 | c:superconductivity^0.5 | h:superconductivity^0.5 | ts:superconductivity^0.5 | cs:superconductivity^0.5 | hs:superconductivity^0.5)~$tb) " +
        "KTextQuery(" +
        s"(t:conductivity$b | c:conductivity | h:conductivity | ts:conductivity$b | cs:conductivity | hs:conductivity | KPrefixQuery(tp-tv: conductivity) |" +
        s" t:superconductivity^0.5 | c:superconductivity^0.5 | h:superconductivity^0.5 | ts:superconductivity^0.5 | cs:superconductivity^0.5 | hs:superconductivity^0.5)~$tb)"

      query = parser.parse("Electromagnetically induced transparency").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        "KTextQuery(" +
        s"(t:electromagnetically$b | c:electromagnetically | h:electromagnetically | ts:electromagnet$b | cs:electromagnet | hs:electromagnet | KPrefixQuery(tp-tv: electromagnetically) |" +
        s" t:electromagneticallyinduced^0.5 | c:electromagneticallyinduced^0.5 | h:electromagneticallyinduced^0.5 |" +
        s" ts:electromagneticallyinduce^0.5 | cs:electromagneticallyinduce^0.5 | hs:electromagneticallyinduce^0.5)~$tb) " +
        "KTextQuery(" +
        s"(t:induced$b | c:induced | h:induced | ts:induce$b | cs:induce | hs:induce | KPrefixQuery(tp-tv: induced) |" +
        s" t:electromagneticallyinduced^0.5 | c:electromagneticallyinduced^0.5 | h:electromagneticallyinduced^0.5 | ts:electromagneticallyinduce^0.5 | cs:electromagneticallyinduce^0.5 | hs:electromagneticallyinduce^0.5 |" +
        s" t:inducedtransparency^0.5 | c:inducedtransparency^0.5 | h:inducedtransparency^0.5 | ts:inducedtransparency^0.5 | cs:inducedtransparency^0.5 | hs:inducedtransparency^0.5)~$tb) " +
        "KTextQuery(" +
        s"(t:transparency$b | c:transparency | h:transparency | ts:transparency$b | cs:transparency | hs:transparency | KPrefixQuery(tp-tv: transparency) |" +
        s" t:inducedtransparency^0.5 | c:inducedtransparency^0.5 | h:inducedtransparency^0.5 | ts:inducedtransparency^0.5 | cs:inducedtransparency^0.5 | hs:inducedtransparency^0.5)~$tb)"

      query = parser.parse("bose-einstein condensate").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        "KTextQuery(" +
        s"""(t:"bose einstein"$b | c:"bose einstein" | h:"bose einstein" | ts:"bose einstein"$b | cs:"bose einstein" | hs:"bose einstein" | KPrefixQuery(tp-tv: bose-einstein) |""" +
        s""" t:boseeinstein^0.5 | c:boseeinstein^0.5 | h:boseeinstein^0.5 | ts:boseeinstein^0.5 | cs:boseeinstein^0.5 | hs:boseeinstein^0.5 |""" +
        s""" t:boseeinsteincondensate^0.5 | c:boseeinsteincondensate^0.5 | h:boseeinsteincondensate^0.5 | ts:boseeinsteincondensate^0.5 | cs:boseeinsteincondensate^0.5 | hs:boseeinsteincondensate^0.5)~$tb) """ +
        "KTextQuery(" +
        s"""(t:condensate$b | c:condensate | h:condensate | ts:condensate$b | cs:condensate | hs:condensate | KPrefixQuery(tp-tv: condensate) |""" +
        s""" t:boseeinsteincondensate^0.5 | c:boseeinsteincondensate^0.5 | h:boseeinsteincondensate^0.5 | ts:boseeinsteincondensate^0.5 | cs:boseeinsteincondensate^0.5 | hs:boseeinsteincondensate^0.5)~$tb)"""

      query = parser.parse("\"Spin-statistics\" theorem").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        s"""KTextQuery((t:"spin statistics"$b | c:"spin statistics" | h:"spin statistics")~$tb) """ +
        s"""KTextQuery((t:theorem$b | c:theorem | h:theorem | ts:theorem$b | cs:theorem | hs:theorem | KPrefixQuery(tp-tv: theorem))~$tb)"""
    }

    "expand queries (with no concat, no prefix search)" in new QueryParserScope(concatBoostValue = 0.0f, trailingPrefixBoost = 0.0f, prefixBoost = 0.0f) {
      var query = parser.parse("super conductivity").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        s"KTextQuery((t:super$b | c:super | h:super | ts:super$b | cs:super | hs:super)~$tb) " +
        s"KTextQuery((t:conductivity$b | c:conductivity | h:conductivity | ts:conductivity$b | cs:conductivity | hs:conductivity)~$tb)"

      query = parser.parse("Electromagnetically induced transparency").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        s"KTextQuery((t:electromagnetically$b | c:electromagnetically | h:electromagnetically | ts:electromagnet$b | cs:electromagnet | hs:electromagnet)~$tb) " +
        s"KTextQuery((t:induced$b | c:induced | h:induced | ts:induce$b | cs:induce | hs:induce)~$tb) " +
        s"KTextQuery((t:transparency$b | c:transparency | h:transparency | ts:transparency$b | cs:transparency | hs:transparency)~$tb)"

      query = parser.parse("bose-einstein condensate").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        s"""KTextQuery((t:"bose einstein"$b | c:"bose einstein" | h:"bose einstein" | ts:"bose einstein"$b | cs:"bose einstein" | hs:"bose einstein")~$tb) """ +
        s"""KTextQuery((t:condensate$b | c:condensate | h:condensate | ts:condensate$b | cs:condensate | hs:condensate)~$tb)"""

      query = parser.parse("\"Spin-statistics\" theorem").get
      query must beAnInstanceOf[KBooleanQuery]
      query.toString ===
        s"""KTextQuery((t:"spin statistics"$b | c:"spin statistics" | h:"spin statistics")~$tb) """ +
        s"""KTextQuery((t:theorem$b | c:theorem | h:theorem | ts:theorem$b | cs:theorem | hs:theorem)~$tb)"""
    }

    "expand a query with site" in new QueryParserScope(concatBoostValue = 0.0f, trailingPrefixBoost = 0.0f, prefixBoost = 0.0f) {
      parser.parse("www.yahoo.com").get.toString ===
        s"""KTextQuery((t:"www yahoo com"$b | c:"www yahoo com" | h:"www yahoo com" | site:www.yahoo.com | ts:"www yahoo com"$b | cs:"www yahoo com" | hs:"www yahoo com")~$tb)"""
    }
  }
}

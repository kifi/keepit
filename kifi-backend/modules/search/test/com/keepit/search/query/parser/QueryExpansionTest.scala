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
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanClause._
import org.apache.lucene.search.BooleanClause._
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.Query
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.util.Version
import com.keepit.search.index.DefaultAnalyzer

class QueryExpansionTest extends Specification {

  private class QueryParserScope(concatBoostValue: Float) extends Scope {
    val analyzer = DefaultAnalyzer.forParsing(Lang("en"))
    val stemmingAnalyzer = DefaultAnalyzer.forParsingWithStemmer(Lang("en"))
    val parser = new QueryParser(analyzer, stemmingAnalyzer) with DefaultSyntax with QueryExpansion {
      override val siteBoost: Float = 1.0f
      override val concatBoost: Float = concatBoostValue
    }
  }

  "QueryExpansion" should {
    "expand queries (with no concat)" in new QueryParserScope(concatBoostValue = 0.0f) {
      parser.parse("super conductivity").get.toString ===
        "TextQuery((title:super | title_stemmed:super)~0.3 (t:super | c:super | site_keywords:super | ts:super | cs:super)~0.3 ()) "+
        "TextQuery((title:conductivity | title_stemmed:conductivity)~0.3 (t:conductivity | c:conductivity | site_keywords:conductivity | ts:conductivity | cs:conductivity)~0.3 ())"

      parser.parse("Electromagnetically induced transparency").get.toString ===
        "TextQuery((title:electromagnetically | title_stemmed:electromagnet)~0.3 (t:electromagnetically | c:electromagnetically | site_keywords:electromagnetically | ts:electromagnet | cs:electromagnet)~0.3 ()) "+
        "TextQuery((title:induced | title_stemmed:induce)~0.3 (t:induced | c:induced | site_keywords:induced | ts:induce | cs:induce)~0.3 ()) "+
        "TextQuery((title:transparency | title_stemmed:transparency)~0.3 (t:transparency | c:transparency | site_keywords:transparency | ts:transparency | cs:transparency)~0.3 ())"

      parser.parse("bose-einstein condensate").get.toString ===
        "TextQuery("+
        """(title:"bose einstein" | title_stemmed:"bose einstein")~0.3 """+
        """(t:"bose einstein" | c:"bose einstein" | ts:"bose einstein" | cs:"bose einstein")~0.3 """ +
        "()) "+
        "TextQuery("+
        """(title:condensate | title_stemmed:condensate)~0.3 (t:condensate | c:condensate | site_keywords:condensate | ts:condensate | cs:condensate)~0.3 """+
        "())"

      parser.parse("\"Spin-statistics\" theorem").get.toString ===
        "TextQuery("+
        """(title:"spin statistics")~0.3 (t:"spin statistics" | c:"spin statistics")~0.3 """+
        "()) "+
        "TextQuery("+
        """(title:theorem | title_stemmed:theorem)~0.3 (t:theorem | c:theorem | site_keywords:theorem | ts:theorem | cs:theorem)~0.3 """ +
        "())"
    }

    "expand queries (with concat)" in new QueryParserScope(concatBoostValue = 0.5f) {
      parser.parse("super conductivity").get.toString ===
        "TextQuery("+
        "(title:super | title_stemmed:super |"+
        " title:superconductivity^0.5 | title_stemmed:superconductivity^0.5)~0.3 "+
        "(t:super | c:super | site_keywords:super | ts:super | cs:super |"+
        " t:superconductivity^0.5 | c:superconductivity^0.5 | site_keywords:superconductivity^0.5 | ts:superconductivity^0.5 | cs:superconductivity^0.5)~0.3 "+
        "()) "+
        "TextQuery("+
        "(title:conductivity | title_stemmed:conductivity |"+
        " title:superconductivity^0.5 | title_stemmed:superconductivity^0.5)~0.3 " +
        "(t:conductivity | c:conductivity | site_keywords:conductivity | ts:conductivity | cs:conductivity |"+
        " t:superconductivity^0.5 | c:superconductivity^0.5 | site_keywords:superconductivity^0.5 | ts:superconductivity^0.5 | cs:superconductivity^0.5)~0.3 "+
        "())"

      parser.parse("Electromagnetically induced transparency").get.toString ===
        "TextQuery("+
        "(title:electromagnetically | title_stemmed:electromagnet | title:electromagneticallyinduced^0.5 | title_stemmed:electromagneticallyinduce^0.5)~0.3 "+
        "(t:electromagnetically | c:electromagnetically | site_keywords:electromagnetically | ts:electromagnet | cs:electromagnet |"+
        " t:electromagneticallyinduced^0.5 | c:electromagneticallyinduced^0.5 | site_keywords:electromagneticallyinduced^0.5 |"+
        " ts:electromagneticallyinduce^0.5 | cs:electromagneticallyinduce^0.5)~0.3 "+
        "()) "+
        "TextQuery("+
        "(title:induced | title_stemmed:induce |"+
        " title:electromagneticallyinduced^0.5 | title_stemmed:electromagneticallyinduce^0.5 | title:inducedtransparency^0.5 | title_stemmed:inducedtransparency^0.5)~0.3 "+
        "(t:induced | c:induced | site_keywords:induced | ts:induce | cs:induce |"+
        " t:electromagneticallyinduced^0.5 | c:electromagneticallyinduced^0.5 | site_keywords:electromagneticallyinduced^0.5 | ts:electromagneticallyinduce^0.5 | cs:electromagneticallyinduce^0.5 |"+
        " t:inducedtransparency^0.5 | c:inducedtransparency^0.5 | site_keywords:inducedtransparency^0.5 | ts:inducedtransparency^0.5 | cs:inducedtransparency^0.5)~0.3 "+
        "()) "+
        "TextQuery("+
        "(title:transparency | title_stemmed:transparency | title:inducedtransparency^0.5 | title_stemmed:inducedtransparency^0.5)~0.3 "+
        "(t:transparency | c:transparency | site_keywords:transparency | ts:transparency | cs:transparency |"+
        " t:inducedtransparency^0.5 | c:inducedtransparency^0.5 | site_keywords:inducedtransparency^0.5 | ts:inducedtransparency^0.5 | cs:inducedtransparency^0.5)~0.3 "+
        "())"

      parser.parse("bose-einstein condensate").get.toString ===
        "TextQuery("+
        """(title:"bose einstein" | title_stemmed:"bose einstein" |"""+
        """ title:boseeinstein^0.5 | title_stemmed:boseeinstein^0.5 | title:boseeinsteincondensate^0.5 | title_stemmed:boseeinsteincondensate^0.5)~0.3 """+
        """(t:"bose einstein" | c:"bose einstein" | ts:"bose einstein" | cs:"bose einstein" |"""+
        """ t:boseeinstein^0.5 | c:boseeinstein^0.5 | site_keywords:boseeinstein^0.5 | ts:boseeinstein^0.5 | cs:boseeinstein^0.5 |"""+
        """ t:boseeinsteincondensate^0.5 | c:boseeinsteincondensate^0.5 | site_keywords:boseeinsteincondensate^0.5 | ts:boseeinsteincondensate^0.5 | cs:boseeinsteincondensate^0.5)~0.3 """+
        "()) "+
        "TextQuery("+
        """(title:condensate | title_stemmed:condensate | title:boseeinsteincondensate^0.5 | title_stemmed:boseeinsteincondensate^0.5)~0.3 """+
        """(t:condensate | c:condensate | site_keywords:condensate | ts:condensate | cs:condensate |"""+
        """ t:boseeinsteincondensate^0.5 | c:boseeinsteincondensate^0.5 | site_keywords:boseeinsteincondensate^0.5 | ts:boseeinsteincondensate^0.5 | cs:boseeinsteincondensate^0.5)~0.3 """+
        "())"

      parser.parse("\"Spin-statistics\" theorem").get.toString ===
        "TextQuery("+
        """(title:"spin statistics")~0.3 """+
        """(t:"spin statistics" | c:"spin statistics")~0.3 """+
        "()) "+
        "TextQuery("+
        """(title:theorem | title_stemmed:theorem)~0.3 """+
        """(t:theorem | c:theorem | site_keywords:theorem | ts:theorem | cs:theorem)~0.3 """+
        "())"
    }
  }
}

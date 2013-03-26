package com.keepit.search

import com.keepit.classify.Domain
import com.keepit.search.phrasedetector.PhraseDetector
import com.keepit.search.query.parser.QueryParser
import com.keepit.search.query.parser.DefaultSyntax
import com.keepit.search.query.parser.PercentMatch
import com.keepit.search.query.parser.QueryExpansion
import com.keepit.search.query.parser.QueryParserException
import com.keepit.search.query.Coordinator
import com.keepit.search.query.ProximityQuery
import com.keepit.search.query.QueryUtil._
import com.keepit.search.query.SemanticVectorQuery
import com.keepit.search.query.SiteQuery
import com.keepit.search.query.TopLevelQuery
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.BooleanClause.Occur._
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import scala.collection.mutable.ArrayBuffer

class MainQueryParser(
  analyzer: Analyzer,
  stemmingAnalyzer: Option[Analyzer],
  baseBoost: Float,
  proximityBoost: Float,
  semanticBoost: Float,
  phraseBoost: Float,
  override val siteBoost: Float,
  phraseDetector: PhraseDetector
) extends QueryParser(analyzer, stemmingAnalyzer) with DefaultSyntax with PercentMatch with QueryExpansion {

  private[this] val phraseDiscount = (1.0f - phraseBoost)

  private def createPhraseQueries(query: BooleanQuery): Option[Query] = {
    var phraseQueries = new BooleanQuery(true)

    phraseDetector.detectAll(getStemmedTermArray).foreach{ phrase =>
      List("ts", "cs", "title_stemmed").foreach{ field =>
        val phraseStart = phrase._1
        val phraseEnd = phraseStart + phrase._2

        // construct a phrase query
        val phraseQuery = getStemmedPhrase(field, phraseStart, phraseEnd)

        phraseQueries.add(phraseQuery, Occur.SHOULD)
      }
      query.add(phraseQueries, Occur.SHOULD)
    }
    if (phraseQueries.clauses.size > 0) {
      phraseQueries.setBoost(phraseBoost)
      Some(phraseQueries)
    } else {
      None
    }
  }

  override def parse(queryText: CharSequence) = {
    super.parse(queryText).map{ query =>
      val terms = getTerms(query)
      if (terms.size <= 0) query
      else {
        query.setBoost(baseBoost)

        val auxQueries = ArrayBuffer.empty[Query]

        if (phraseBoost > 0.0f) {
          query match {
            case query: BooleanQuery => createPhraseQueries(query).foreach{ q => auxQueries += q }
            case _ =>
          }
        }

        if (semanticBoost > 0.0f) {
          val svq = SemanticVectorQuery("sv", terms)
          svq.setBoost(semanticBoost)
          auxQueries += svq
        }

        if (numStemmedTerms > 1 && proximityBoost > 0.0f) {
          val proxQ = new BooleanQuery(true)
          proxQ.add(ProximityQuery(getStemmedTerms("cs")), SHOULD)
          proxQ.add(ProximityQuery(getStemmedTerms("ts")), SHOULD)
          proxQ.add(ProximityQuery(getStemmedTerms("title_stemmed")), SHOULD)
          proxQ.setBoost(proximityBoost)
          auxQueries += proxQ
        }
        new TopLevelQuery(query, auxQueries.toArray, enableCoord)
      }
    }
  }
}

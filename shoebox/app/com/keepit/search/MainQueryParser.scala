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
import com.keepit.search.query.AdditiveBoostQuery
import com.keepit.search.query.MultiplicativeBoostQuery
import com.keepit.search.query.BoostQuery

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

        val textQuery = query match {
          case query: BooleanQuery if (phraseBoost > 0.0f) =>
            createPhraseQueries(query).map{ q => new AdditiveBoostQuery(query, Array[Query](q)) }.getOrElse(query)
          case _ => query
        }

        val auxQueries = ArrayBuffer.empty[Query]
        val auxStrengths = ArrayBuffer.empty[Float]

        if (semanticBoost > 0.0f) {
          val svq = SemanticVectorQuery("sv", terms)
          auxQueries += svq
          auxStrengths += semanticBoost
        }

        if (numStemmedTerms > 1 && proximityBoost > 0.0f) {
          val proxQ = new BooleanQuery(true)
          proxQ.add(ProximityQuery(getStemmedTerms("cs")), SHOULD)
          proxQ.add(ProximityQuery(getStemmedTerms("ts")), SHOULD)
          proxQ.add(ProximityQuery(getStemmedTerms("title_stemmed")), SHOULD)
          auxQueries += proxQ
          auxStrengths += proximityBoost
        }

        val topLevelQuery = if (!auxQueries.isEmpty) {
          new MultiplicativeBoostQuery(textQuery, auxQueries.toArray, auxStrengths.toArray)
        } else {
          textQuery
        }

        topLevelQuery match {
          case q: BoostQuery => q.enableCoord = enableCoord
          case _ =>
        }
        topLevelQuery
      }
    }
  }
}

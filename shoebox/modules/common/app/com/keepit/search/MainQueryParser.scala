package com.keepit.search

import com.keepit.classify.Domain
import com.keepit.search.phrasedetector.{PhraseDetector, NlpPhraseDetector}
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
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.BooleanClause.Occur._
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.DisjunctionMaxQuery
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import scala.collection.mutable.ArrayBuffer
import com.keepit.search.query.AdditiveBoostQuery
import com.keepit.search.query.MultiplicativeBoostQuery
import com.keepit.search.query.BoostQuery
import com.keepit.search.query.PhraseProximityQuery
import com.keepit.search.query.NamedQueryContext
import com.keepit.search.query.NamedQuery
import scala.concurrent._
import ExecutionContext.Implicits.global


class MainQueryParser(
  analyzer: Analyzer,
  stemmingAnalyzer: Analyzer,
  baseBoost: Float,
  proximityBoost: Float,
  semanticBoost: Float,
  phraseBoost: Float,
  phraseProximityBoost: Float,
  override val siteBoost: Float,
  phraseDetector: PhraseDetector
) extends QueryParser(analyzer, stemmingAnalyzer) with DefaultSyntax with PercentMatch with QueryExpansion {

  val namedQueryContext = new NamedQueryContext

  private def createPhraseQueries(phrases: Set[(Int, Int)]): Query = {
    var phraseQueries = new BooleanQuery(true)

    phrases.foreach{ phrase =>
      List("ts", "cs", "title_stemmed").foreach{ field =>
        val phraseStart = phrase._1
        val phraseEnd = phraseStart + phrase._2

        // construct a phrase query
        val phraseQuery = getStemmedPhrase(field, phraseStart, phraseEnd)

        phraseQueries.add(phraseQuery, Occur.SHOULD)
      }
    }
    phraseQueries.setBoost(phraseBoost)
    phraseQueries
  }

  private def namedQuery(name: String, query: Query) = new NamedQuery(name, query, namedQueryContext)

  override def parse(queryText: CharSequence): Option[Query] = {
    super.parse(queryText).map{ query =>
      if (numStemmedTerms <= 0) query
      else {
        query.setBoost(baseBoost)

        // doing nothing. block nothing. Just some tests.
        future {
          val tic = System.currentTimeMillis
          val phrases = NlpPhraseDetector.detectAll(queryText.toString, stemmingAnalyzer)
          val offsets = getTermOffsets(stemmingAnalyzer, queryText.toString)
          val elapsed = System.currentTimeMillis - tic
          log.info("nlp phrase detector time elapsed: " + elapsed)
          log.info("term offsets: " + offsets.toSeq.toString)
          log.info("detected phrases: " + phrases.toSeq.toString)
        }

        val phrases = if (numStemmedTerms > 1 && (phraseBoost > 0.0f || phraseProximityBoost > 0.0f)) {
          phraseDetector.detectAll(getStemmedTermArray)



        } else {
          Set.empty[(Int, Int)]
        }

        val textQuery = if (phraseBoost > 0.0f && phrases.nonEmpty) {
          new AdditiveBoostQuery(query, Array[Query](createPhraseQueries(phrases)))
        } else {
          query
        }

        val auxQueries = ArrayBuffer.empty[Query]
        val auxStrengths = ArrayBuffer.empty[Float]

        if (semanticBoost > 0.0f) {
          val svq = SemanticVectorQuery(getStemmedTerms("sv"), fallbackField = "title_stemmed")
          auxQueries += namedQuery("semantic vector", svq)
          auxStrengths += semanticBoost
        }

        if (phraseProximityBoost > 0.0f && phrases.nonEmpty) {
          val phraseProxQ = new DisjunctionMaxQuery(0.0f)
          phraseProxQ.add( PhraseProximityQuery(getStemmedTerms("cs"), phrases))
          phraseProxQ.add( PhraseProximityQuery(getStemmedTerms("ts"), phrases))
          phraseProxQ.add( PhraseProximityQuery(getStemmedTerms("title_stemmed"), phrases))
          auxQueries += namedQuery("proximity", phraseProxQ)
          auxStrengths += phraseProximityBoost
        } else if (proximityBoost > 0.0f && numStemmedTerms > 1) {
          val proxQ = new DisjunctionMaxQuery(0.0f)
          proxQ.add(ProximityQuery(getStemmedTerms("cs")))
          proxQ.add(ProximityQuery(getStemmedTerms("ts")))
          proxQ.add(ProximityQuery(getStemmedTerms("title_stemmed")))
          auxQueries += namedQuery("proximity", proxQ)
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

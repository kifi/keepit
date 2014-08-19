package com.keepit.search.engine.parser

import org.apache.lucene.index.Term
import org.apache.lucene.search._
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.akka.SafeFuture
import com.keepit.common.service.RequestConsolidator
import com.keepit.search.engine.QueryEngineBuilder
import com.keepit.search.{ SearchConfig, Lang }
import com.keepit.search.index.Analyzer
import com.keepit.search.phrasedetector.PhraseDetector
import com.keepit.search.query._
import com.keepit.search.query.parser.{ DefaultSyntax, QueryParser }
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class KQueryParser(
    analyzer: Analyzer,
    stemmingAnalyzer: Analyzer,
    altAnalyzer: Option[Analyzer],
    altStemmingAnalyzer: Option[Analyzer],
    config: SearchConfig,
    phraseDetector: PhraseDetector,
    phraseDetectionConsolidator: RequestConsolidator[(CharSequence, Lang), Set[(Int, Int)]],
    monitoredAwait: MonitoredAwait) { qp =>

  private[this] val parser = new QueryParser(analyzer, stemmingAnalyzer) with DefaultSyntax with KQueryExpansion {
    override val altAnalyzer = qp.altAnalyzer
    override val altStemmingAnalyzer = qp.altStemmingAnalyzer
    override val siteBoost = config.asFloat("siteBoost")
    override val concatBoost = config.asFloat("concatBoost")
    override val lang: Lang = qp.analyzer.lang
  }

  private[this] val proximityBoost = config.asFloat("proximityBoost")
  private[this] val semanticBoost = config.asFloat("semanticBoost")
  private[this] val phraseBoost = config.asFloat("phraseBoost")
  private[this] val homePageBoost = config.asFloat("homePageBoost")
  private[this] val proximityGapPenalty = config.asFloat("proximityGapPenalty")
  private[this] val proximityThreshold = config.asFloat("proximityThreshold")
  private[this] val proximityPowerFactor = config.asFloat("proximityPowerFactor")

  var totalParseTime: Long = 0L

  def parse(queryText: CharSequence): Option[QueryEngineBuilder] = {
    val tParse = System.currentTimeMillis

    val builderOpt = parser.parse(queryText).map { query =>
      val numTextQueries = parser.textQueries.size

      if (numTextQueries <= 0 || numTextQueries > ProximityQuery.maxLength) { // no terms or too many terms, skip proximity and semantic vector
        new QueryEngineBuilder(query)
      } else {
        val phrasesFuture = if (numTextQueries > 1 && phraseBoost > 0.0f) detectPhrases(queryText, parser.lang) else null

        if (semanticBoost > 0.0f) {
          parser.textQueries.foreach { textQuery =>
            textQuery.setSemanticBoost(semanticBoost)
            textQuery.stems.map { stemTerm => textQuery.addSemanticVectorQuery("sv", stemTerm.text) }
          }
        }

        val engBuilder = new QueryEngineBuilder(query)

        if (proximityBoost > 0.0f && numTextQueries > 1) {
          val phrases = if (phrasesFuture != null) monitoredAwait.result(phrasesFuture, 3 seconds, "phrase detection") else Set.empty[(Int, Int)]
          val proxQ = new DisjunctionMaxQuery(0.0f)
          proxQ.add(ProximityQuery(proxTermsFor("cs"), phrases, phraseBoost, proximityGapPenalty, proximityThreshold, proximityPowerFactor))
          proxQ.add(ProximityQuery(proxTermsFor("ts"), Set(), 0f, proximityGapPenalty, proximityThreshold, 1f)) // disable phrase scoring for title. penalty could be too big
          engBuilder.addBoosterQuery(proxQ, proximityBoost)
        } else if (numTextQueries == 1 && phTerms.nonEmpty && homePageBoost > 0.0f) {
          val homePageQuery = if (phTerms.size == 1) {
            new FixedScoreQuery(new TermQuery(new Term("home_page", phTerms(0).text)))
          } else {
            val hpQ = new PhraseQuery()
            phTerms.foreach { t => hpQ.add(new Term("home_page", t.text)) }
            new FixedScoreQuery(hpQ)
          }
          engBuilder.addBoosterQuery(homePageQuery, homePageBoost)
        }

        engBuilder
      }
    }
    totalParseTime = System.currentTimeMillis - tParse

    builderOpt
  }

  private[this] lazy val phTerms: IndexedSeq[Term] = {
    parser.textQueries.flatMap { _.terms }
  }
  private[this] lazy val phStemmedTerms: IndexedSeq[Term] = {
    parser.textQueries.flatMap { _.stems }
  }

  private[this] def proxTermsFor(field: String): Seq[Seq[Term]] = {
    parser.textQueries.foldLeft(new ArrayBuffer[ArrayBuffer[Term]]) { (terms, q) =>
      val concatTerms = q.concatStems.map { new Term(field, _) }
      q.stems.foreach { t =>
        val buf = ArrayBuffer(new Term(field, t.text))
        buf ++= concatTerms
        terms += buf
      }
      terms
    }
  }

  def svTerms: Seq[Term] = {
    parser.textQueries.flatMap { _.stems.map { t => new Term("sv", t.text) } }
  }

  private def detectPhrases(queryText: CharSequence, lang: Lang): Future[Set[(Int, Int)]] = {
    phraseDetectionConsolidator((queryText, lang)) { _ =>
      SafeFuture { phraseDetector.detectAll(phStemmedTerms) }
    }
  }
}

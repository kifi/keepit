package com.keepit.search.engine.parser

import com.keepit.search.engine.query.core.KProximityQuery
import org.apache.lucene.index.Term
import com.keepit.common.service.RequestConsolidator
import com.keepit.search.engine.QueryEngineBuilder
import com.keepit.search.{ SearchConfig, Lang }
import com.keepit.search.index.Analyzer
import com.keepit.search.index.phrase.PhraseDetector
import com.keepit.search.engine.query._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.DurationInt

class KQueryParser(
    analyzer: Analyzer,
    stemmingAnalyzer: Analyzer,
    altAnalyzer: Option[Analyzer],
    altStemmingAnalyzer: Option[Analyzer],
    disablePrefixSearch: Boolean,
    config: SearchConfig,
    phraseDetector: PhraseDetector,
    phraseDetectionConsolidator: RequestConsolidator[(CharSequence, Lang), Set[(Int, Int)]]) { qp =>

  private[this] val parser = new QueryParser(analyzer, stemmingAnalyzer) with DefaultSyntax with KQueryExpansion {
    val altAnalyzer = qp.altAnalyzer
    val altStemmingAnalyzer = qp.altStemmingAnalyzer
    val titleBoost = config.asFloat("titleBoost")
    val siteBoost = config.asFloat("siteBoost")
    val concatBoost = config.asFloat("concatBoost")
    val prefixBoost = if (disablePrefixSearch) 0.0f else config.asFloat("prefixBoost")
    val lang: Lang = qp.analyzer.lang
  }

  private[this] val phraseBoost = config.asFloat("phraseBoost")
  private[this] val homePageBoost = config.asFloat("homePageBoost")
  private[this] val proximityBoost = config.asFloat("proximityBoost")
  private[this] val proximityGapPenalty = config.asFloat("proximityGapPenalty")
  private[this] val proximityPowerFactor = config.asFloat("proximityPowerFactor")

  def parse(queryText: CharSequence): Option[QueryEngineBuilder] = {

    val builderOpt = parser.parse(queryText).map { query =>
      val engBuilder = new QueryEngineBuilder(query)
      val numTextQueries = parser.textQueries.size

      if (0 < numTextQueries && numTextQueries <= ProximityQuery.maxLength) { // no terms or too many terms, skip proximity/home page boost

        if (proximityBoost > 0.0f && numTextQueries > 1) {

          val phrases = detectPhrases(queryText, parser.lang)
          val proxQ = new KProximityQuery
          proxQ.add(ProximityQuery(proxTermsFor("cs"), phrases, phraseBoost, proximityGapPenalty, proximityPowerFactor))
          proxQ.add(ProximityQuery(proxTermsFor("ts"), Set(), 0f, proximityGapPenalty, 1f)) // disable phrase scoring for title. penalty could be too big
          engBuilder.addBoosterQuery(proxQ, proximityBoost)

        } else if (numTextQueries == 1 && phTerms.nonEmpty && homePageBoost > 0.0f) {

          engBuilder.addBoosterQuery(new HomePageQuery(phTerms), homePageBoost)

        }
      }
      engBuilder
    }

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

  private def detectPhrases(queryText: CharSequence, lang: Lang): Set[(Int, Int)] = {
    val future = phraseDetectionConsolidator((queryText, lang)) { _ =>
      Future.successful { phraseDetector.detectAll(phStemmedTerms) }
    }
    Await.result(future, 100 millisecond)
  }
}

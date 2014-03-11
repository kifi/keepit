package com.keepit.search.query.parser

import scala.concurrent.duration.DurationInt

import com.google.inject.Inject
import com.google.inject.Singleton
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.service.RequestConsolidator
import com.keepit.search.Lang
import com.keepit.search.SearchConfig
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.phrasedetector.PhraseDetector


@Singleton
class MainQueryParserFactory @Inject() (phraseDetector: PhraseDetector, monitoredAwait: MonitoredAwait) {

  private val phraseDetectionConsolidator = new RequestConsolidator[(CharSequence, Lang), Set[(Int, Int)]](10 minutes)

  def apply(lang1: Lang, lang2: Option[Lang], config: SearchConfig): MainQueryParser = {
    val proximityBoost = config.asFloat("proximityBoost")
    val semanticBoost = config.asFloat("semanticBoost")
    val phraseBoost = config.asFloat("phraseBoost")
    val siteBoost = config.asFloat("siteBoost")
    val concatBoost = config.asFloat("concatBoost")
    val homePageBoost = config.asFloat("homePageBoost")
    val useSemanticMatch = config.asBoolean("useSemanticMatch")
    val proximityGapPenalty = config.asFloat("proximityGapPenalty")
    val proximityThreshold = config.asFloat("proximityThreshold")
    val proximityPowerFactor = config.asFloat("proximityPowerFactor")

    new MainQueryParser(
      DefaultAnalyzer.forParsing(lang1),
      DefaultAnalyzer.forParsingWithStemmer(lang1),
      lang2.map(DefaultAnalyzer.forParsing(_)),
      lang2.map(DefaultAnalyzer.forParsingWithStemmer(_)),
      proximityBoost,
      semanticBoost,
      phraseBoost,
      siteBoost,
      concatBoost,
      homePageBoost,
      useSemanticMatch,
      proximityGapPenalty,
      proximityThreshold,
      proximityPowerFactor,
      phraseDetector,
      phraseDetectionConsolidator,
      monitoredAwait
    )
  }
}

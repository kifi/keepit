package com.keepit.search

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.service.RequestConsolidator
import com.keepit.search.phrasedetector.PhraseDetector
import com.keepit.search.index.DefaultAnalyzer
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import scala.concurrent.duration._

@Singleton
class MainQueryParserFactory @Inject() (phraseDetector: PhraseDetector, monitoredAwait: MonitoredAwait) {

  private val phraseDetectionConsolidator = new RequestConsolidator[(CharSequence, Lang), Set[(Int, Int)]](10 minutes)

  def apply(lang: Lang, config: SearchConfig): MainQueryParser = {
    val proximityBoost = config.asFloat("proximityBoost")
    val semanticBoost = config.asFloat("semanticBoost")
    val phraseBoost = config.asFloat("phraseBoost")
    val siteBoost = config.asFloat("siteBoost")
    val concatBoost = config.asFloat("concatBoost")
    val homePageBoost = config.asFloat("homePageBoost")
    val useSemanticMatch = config.asBoolean("useSemanticMatch")
    val proximityGapPenalty = config.asFloat("proximityGapPenalty")

    new MainQueryParser(
      lang,
      DefaultAnalyzer.forParsing(lang),
      DefaultAnalyzer.forParsingWithStemmer(lang),
      proximityBoost,
      semanticBoost,
      phraseBoost,
      siteBoost,
      concatBoost,
      homePageBoost,
      useSemanticMatch,
      proximityGapPenalty,
      phraseDetector,
      phraseDetectionConsolidator,
      monitoredAwait
    )
  }
}

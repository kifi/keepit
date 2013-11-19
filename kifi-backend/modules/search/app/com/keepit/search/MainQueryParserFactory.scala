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

  def apply(lang: Lang, proximityBoost: Float = 0.0f, semanticBoost: Float = 0.0f, phraseBoost: Float = 0.0f,
            siteBoost: Float = 0.0f, concatBoost: Float = 0.0f, homePageBoost: Float = 0.0f): MainQueryParser = {
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
      phraseDetector,
      phraseDetectionConsolidator,
      monitoredAwait
    )
  }
}

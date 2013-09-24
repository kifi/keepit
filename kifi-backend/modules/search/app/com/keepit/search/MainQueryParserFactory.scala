package com.keepit.search

import com.keepit.search.phrasedetector.PhraseDetector
import com.keepit.search.index.DefaultAnalyzer
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._

@Singleton
class MainQueryParserFactory @Inject() (phraseDetector: PhraseDetector) {
  def apply(lang: Lang, proximityBoost: Float = 0.0f, semanticBoost: Float = 0.0f, phraseBoost: Float = 0.0f,
            siteBoost: Float = 0.0f): MainQueryParser = {
    new MainQueryParser(
      lang,
      DefaultAnalyzer.forParsing(lang),
      DefaultAnalyzer.forParsingWithStemmer(lang),
      proximityBoost,
      semanticBoost,
      phraseBoost,
      siteBoost,
      phraseDetector
    )
  }
}

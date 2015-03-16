package com.keepit.scraper.extractor

import com.keepit.common.net.URI
import com.keepit.common.logging.Logging
import com.google.inject.{ Singleton, Inject }

@Singleton
class ExtractorFactoryImpl @Inject() (
    youtubeExtractorProvider: YoutubeExtractorProvider,
    simpleJsoupBasedExtractorProvider: SimpleJsoupBasedExtractorProvider,
    linkProcessingExtractorProvider: LinkProcessingExtractorProvider) extends ExtractorFactory with Logging {

  val all = Seq(
    youtubeExtractorProvider,
    GithubExtractorProvider,
    LinkedInExtractorProvider,
    simpleJsoupBasedExtractorProvider,
    linkProcessingExtractorProvider
  )

  def apply(uri: URI): Extractor = {
    try {
      all.find(_.isDefinedAt(uri)).map { f =>
        f.apply(uri)
      }.getOrElse(throw new Exception("failed to find an extractor factory"))
    } catch {
      case e: Throwable =>
        log.warn(s"uri parsing failed: [$uri][$e]")
        DefaultExtractorProvider(uri)
    }
  }
}

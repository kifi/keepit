package com.keepit.scraper.extractor

import com.keepit.scraper.{HttpFetcher, HttpInputStream}
import com.keepit.common.net.URI
import scala.util.{Failure, Success}
import com.keepit.common.logging.Logging
import com.google.inject.{Singleton, Inject}

@Singleton
class ExtractorFactories @Inject() (httpFetcher: HttpFetcher) extends Logging {
  val all = Seq(
    YoutubeExtractorProvider(httpFetcher),
    GithubExtractorProvider,
    LinkedInExtractorProvider,
    DefaultExtractorProvider
  )

  def getExtractor(url: String): Extractor = {
    try {
      URI.parse(url) match {
        case Success(uri) =>
          all.find(_.isDefinedAt(uri)).map{ f =>
            f.apply(uri)
          }.getOrElse(throw new Exception("failed to find an extractor factory"))
        case Failure(_) =>
          log.warn("uri parsing failed: [%s]".format(url))
          DefaultExtractorProvider(url)
      }
    } catch {
      case e: Throwable =>
        log.warn("uri parsing failed: [%s][%s]".format(url, e.toString))
        DefaultExtractorProvider(url)
    }
  }
}

trait Extractor {
  def process(input: HttpInputStream): Unit
  def getContent(): String
  def getMetadata(name: String): Option[String]
  def getKeywords(): Option[String] = None
}

abstract class ExtractorProvider extends PartialFunction[URI, Extractor]


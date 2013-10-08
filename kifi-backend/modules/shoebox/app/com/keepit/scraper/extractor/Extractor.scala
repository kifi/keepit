package com.keepit.scraper.extractor

import com.keepit.scraper.HttpInputStream
import com.keepit.common.net.URI
import scala.util.{Failure, Success}
import com.keepit.common.logging.Logging
import com.google.inject.{ImplementedBy, Singleton, Inject}

trait Extractor {
  def process(input: HttpInputStream): Unit
  def getContent(): String
  def getMetadata(name: String): Option[String]
  def getKeywords(): Option[String]
}

@ImplementedBy(classOf[ExtractorFactoryImpl])
trait ExtractorFactory extends Function[String, Extractor]

@Singleton
class ExtractorFactoryImpl @Inject() (youtubeExtractorProvider: YoutubeExtractorProvider, linkProcessingExtractorProvider: LinkProcessingExtractorProvider) extends ExtractorFactory with Logging {

  val all = Seq(
    youtubeExtractorProvider,
    GithubExtractorProvider,
    LinkedInExtractorProvider,
    linkProcessingExtractorProvider
  )

  def apply(url: String): Extractor = {
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

abstract class ExtractorProvider extends PartialFunction[URI, Extractor]

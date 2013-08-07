package com.keepit.normalizer

import com.keepit.common.logging.Logging
import com.keepit.model.NormalizedURI
import scala.util.{Failure, Success}
import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.net.URI

@ImplementedBy(classOf[SmartNormalizationService])
case class NormalizationService(normalizers: URINormalizer*) extends SmartNormalizer with Logging {
  def isDefinedAt(uri: URI) = normalizers.exists(_.isDefinedAt(uri))
  def apply(uri: URI): URI = normalizers.find(_.isDefinedAt(uri)).get.apply(uri)
  def update(current: NormalizedURI, candidates: NormalizationCandidate*) = normalizers.filter(_.isSmart).foreach(_.update(current, candidates:_*))

  private def parse(uriString: String): Option[URI] = URI.parse(uriString) match {
    case Success(uri) => Some(uri)
    case Failure(e) =>
      log.error("uri parsing failed: [%s] caused by [%s]".format(uriString, e.getMessage))
      None
  }

  def parseAndNormalize(uriString: String): Option[URI] = parse(uriString).map(apply(_))

  def normalize(uriString: String): String = {
    val normalizedUriStringOption = parseAndNormalize(uriString).map { uri =>
      try {
        Some(uri.toString())
      } catch { case e : Exception =>
        URI.log.error("URI.toString() failed: [%s] caused by [%s]".format(uriString, e.getMessage))
        None
      }
    }
    normalizedUriStringOption.flatten.getOrElse(uriString)
  }

}

object StaticNormalizationService extends NormalizationService(
  AmazonNormalizer,
  GoogleNormalizer,
  YoutubeNormalizer,
  RemoveWWWNormalizer,
  LinkedInNormalizer,
  DefaultNormalizer
)

@Singleton
class SmartNormalizationService @Inject() (smartNormalizer: SmartNormalizer) extends NormalizationService(smartNormalizer)

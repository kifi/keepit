package com.keepit.normalizer

import com.keepit.model.NormalizedURI
import com.keepit.common.net.URI
import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.scraper.ScraperPlugin

trait URINormalizer extends PartialFunction[URI, URI] {
  def isSmart: Boolean
  def update(current: NormalizedURI, candidates: NormalizationCandidate*): Unit
}

trait StaticNormalizer extends URINormalizer {
  val isSmart = false
  def update(current: NormalizedURI, candidates: NormalizationCandidate*) = throw new Exception("This normalizer cannot be updated")
}

@ImplementedBy(classOf[SmartNormalizerImpl])
trait SmartNormalizer extends URINormalizer {
  val isSmart = true
}

@Singleton
class SmartNormalizerImpl @Inject() (scraper: ScraperPlugin) extends SmartNormalizer {
  def isDefinedAt(uri: URI) = true // smart normalizer should always be applicable
  def apply(uri: URI) = uri // ok, it is actually pretty dumb at this point
  def update(uri: NormalizedURI, candidates: NormalizationCandidate*): Unit = {}
}

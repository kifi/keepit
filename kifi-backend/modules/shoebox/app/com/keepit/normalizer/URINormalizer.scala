package com.keepit.normalizer

import com.keepit.common.net.URI
import scala.util.Try

trait URINormalizer extends PartialFunction[URI, URI]
trait StaticNormalizer extends URINormalizer

object Prenormalizer extends StaticNormalizer {

  val serialNormalizers: Seq[StaticNormalizer] = Seq(DefaultPageNormalizer)
  val parallelNormalizers: Seq[StaticNormalizer] =
    Seq(QuoraNormalizer, WikipediaNormalizer, AmazonNormalizer, GoogleNormalizer, YoutubeNormalizer, LinkedInNormalizer, DefaultNormalizer)

  def isDefinedAt(uri: URI) = parallelNormalizers.exists(_.isDefinedAt(uri))
  def apply(uri: URI) = applyAll(applyFirst(uri, parallelNormalizers), serialNormalizers)

  private def applyAll(uri: URI, normalizers: Seq[StaticNormalizer]) = normalizers.foldLeft(uri)((u, n) => n.applyOrElse(u, identity[URI]))
  private def applyFirst(uri: URI, normalizers: Seq[StaticNormalizer]) = normalizers.find(_.isDefinedAt(uri)).map(_.apply(uri)).get

  // For convenient testing / debugging:
  def apply(url: String): Try[String] = for {
    parsedUri <- URI.parse(url)
    prenormalizedUri <- Try { Prenormalizer(parsedUri) }
  } yield prenormalizedUri.toString()
}

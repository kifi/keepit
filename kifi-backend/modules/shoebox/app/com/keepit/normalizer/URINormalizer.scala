package com.keepit.normalizer

import com.keepit.common.net.URI

trait URINormalizer extends PartialFunction[URI, URI]
trait StaticNormalizer extends URINormalizer

object Prenormalizer extends StaticNormalizer {

  val serialNormalizers: Seq[StaticNormalizer] = Seq(DefaultPageNormalizer)
  val parallelNormalizers: Seq[StaticNormalizer] =
    Seq(AmazonNormalizer, GoogleNormalizer, YoutubeNormalizer, RemoveWWWNormalizer, LinkedInNormalizer, DefaultNormalizer)

  def isDefinedAt(uri: URI) = parallelNormalizers.exists(_.isDefinedAt(uri))
  def apply(uri: URI) = applyAll(applyFirst(uri, parallelNormalizers), serialNormalizers)
  def apply(url: String): String = URI.safelyParse(url).map(Prenormalizer).flatMap(_.safelyToString()).getOrElse(url)

  private def applyAll(uri: URI, normalizers: Seq[StaticNormalizer]) = normalizers.foldLeft(uri)((u, n) => n.applyOrElse(u, identity[URI]))
  private def applyFirst(uri: URI, normalizers: Seq[StaticNormalizer]) = normalizers.find(_.isDefinedAt(uri)).map(_.apply(uri)).get

}

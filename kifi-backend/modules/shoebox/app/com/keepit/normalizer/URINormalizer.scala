package com.keepit.normalizer

import com.keepit.common.net.URI

trait URINormalizer extends PartialFunction[URI, URI]
trait StaticNormalizer extends URINormalizer

object Prenormalizer extends StaticNormalizer {
  val normalizers: Seq[StaticNormalizer] =
    Seq(AmazonNormalizer, GoogleNormalizer, YoutubeNormalizer, RemoveWWWNormalizer, LinkedInNormalizer, DefaultNormalizer)

  def isDefinedAt(uri: URI) = normalizers.exists(_.isDefinedAt(uri))
  def apply(uri: URI) =  normalizers.find(_.isDefinedAt(uri)).map(_.apply(uri)).get
  def apply(url: String): String = URI.safelyParse(url).map(Prenormalizer).flatMap(_.safelyToString()).getOrElse(url)
}

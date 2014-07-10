package com.keepit.normalizer

import com.keepit.model.Normalization
import com.keepit.common.net.{ Host, URI }

case class SchemeNormalizer(normalization: Normalization) extends StaticNormalizer {
  require(Normalization.schemes.contains(normalization))
  def isDefinedAt(uri: URI) = true
  def apply(uri: URI) = normalization.scheme.split("://") match {
    case Array(httpScheme) => URI(None, Some(httpScheme), uri.userInfo, uri.host.map(cleanPrefix(_)), uri.port, uri.path, uri.query, uri.fragment)
    case Array(httpScheme, domainPrefix) => URI(None, Some(httpScheme), uri.userInfo, uri.host.map(cleanPrefix(_)).map(addPrefix(_, domainPrefix)), uri.port, uri.path, uri.query, uri.fragment)
  }

  private def cleanPrefix(host: Host, prefixes: Set[String] = Set("www", "m", "mobile")) = host match {
    case Host(domain @ _*) if (domain.nonEmpty && (prefixes.contains(domain.last))) => Host(domain.take(domain.size - 1): _*)
    case _ => host
  }

  private def addPrefix(host: Host, prefix: String) = Host(host.domain :+ prefix: _*)

}

object SchemeNormalizer {
  def generateVariations(url: String): Seq[(Normalization, String)] = URI.safelyParse(url).toSeq.flatMap(generateVariations)

  def findSchemeNormalization(url: String): Option[Normalization] = URI.safelyParse(url).flatMap { uri =>
    val parsed = uri.toString()
    generateVariations(uri).collectFirst { case (normalization, variation) if variation == parsed => normalization }
  }

  private def generateVariations(uri: URI): Seq[(Normalization, String)] = Normalization.schemes.toSeq.map { normalization => (normalization, SchemeNormalizer(normalization)(uri).toString()) }
}

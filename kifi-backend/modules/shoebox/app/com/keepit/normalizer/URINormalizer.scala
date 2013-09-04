package com.keepit.normalizer

import com.keepit.common.net.URI

trait URINormalizer extends PartialFunction[URI, URI]
trait StaticNormalizer extends URINormalizer

object Prenormalizer extends StaticNormalizer {
  val normalizers: Seq[StaticNormalizer] =
    Seq(AmazonNormalizer, GoogleNormalizer, YoutubeNormalizer, RemoveWWWNormalizer, LinkedInNormalizer, DefaultNormalizer)

  def isDefinedAt(uri: URI) = normalizers.exists(_.isDefinedAt(uri))
  def apply(uri: URI) = {
    // do default page normalization before calling normalizers
    val uri2 = uri match {
      case URI(scheme, userInfo, host, port, path, query, fragment) =>
        URI(uri.raw, scheme, userInfo, host, port, normalizePath(path), query, fragment)
    }

    normalizers.find(_.isDefinedAt(uri2)).map(_.apply(uri2)).get
  }
  def apply(url: String): String = URI.safelyParse(url).map(Prenormalizer).flatMap(_.safelyToString()).getOrElse(url)

  // default page normalization (moved from URI class)
  private[this] val defaultPage = """/(index|default)\.(html|htm|asp|aspx|php|php3|php4|phtml|cfm|cgi|jsp|jsf|jspx|jspa)$""".r
  private def normalizePath(path: Option[String]): Option[String] = {
    path.map{ path =>
      defaultPage.findFirstMatchIn(path.toLowerCase) match {
        case Some(m) =>
          val delta = path.length - path.toLowerCase.length // in case the case conversion changed the length
          path.substring(0, m.start + delta) + "/"
        case _ => path
      }
    }
  }
}

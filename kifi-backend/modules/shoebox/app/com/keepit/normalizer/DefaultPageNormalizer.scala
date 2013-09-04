package com.keepit.normalizer

import com.keepit.common.logging.Logging
import com.keepit.common.net.URI

object DefaultPageNormalizer extends StaticNormalizer with Logging {

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

  def isDefinedAt(uri: URI) = {
    uri.path.exists{ path => defaultPage.findFirstMatchIn(path.toLowerCase).isDefined }
  }

  def apply(uri: URI) = {
    uri match {
      case URI(scheme, userInfo, host, port, path, query, fragment) =>
        URI(uri.raw, scheme, userInfo, host, port, normalizePath(path), query, fragment)
    }
  }
}

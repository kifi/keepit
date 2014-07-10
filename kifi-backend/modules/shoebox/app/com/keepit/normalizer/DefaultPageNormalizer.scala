package com.keepit.normalizer

import com.keepit.common.logging.Logging
import com.keepit.common.net.URI

object DefaultPageNormalizer extends StaticNormalizer with Logging {

  // default page normalization (moved from URI class)
  private[this] val defaultPage = """(?i)/(index|default)\.(html|htm|asp|aspx|php|php3|php4|phtml|cfm|cgi|jsp|jsf|jspx|jspa)$""".r

  private def normalizePath(path: Option[String]): Option[String] = {
    path.map { defaultPage.replaceFirstIn(_, "/") }
  }

  def isDefinedAt(uri: URI) = {
    uri.path.exists { path => defaultPage.findFirstMatchIn(path).isDefined }
  }

  def apply(uri: URI) = {
    uri match {
      case URI(scheme, userInfo, host, port, path, query, fragment) =>
        URI(uri.raw, scheme, userInfo, host, port, normalizePath(path), query, fragment)
    }
  }
}

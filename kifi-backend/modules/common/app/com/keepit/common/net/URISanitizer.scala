package com.keepit.common.net

import com.keepit.common.logging.Logging

object URISanitizer extends Logging {

  private[this] val parser = new URIParserGrammar {
    override def normalizeParams(params: Seq[Param]): Seq[Param] = params
  }

  def sanitize(uriString: String): String = {
    try {
      val raw = uriString.trim
      if (raw.isEmpty) raw else {
        val uri = parser.parseAll(parser.uri, raw).get
        uri.toString
      }
    } catch {
      case e: Throwable =>
        log.error("uri parsing failed: [%s] caused by [%s]".format(uriString, e.getMessage))
        uriString
    }
  }
}

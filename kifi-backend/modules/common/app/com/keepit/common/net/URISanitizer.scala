package com.keepit.common.net

import com.keepit.common.logging.Logging

object URISanitizer extends Logging {

  private[this] val parser = new URIParserGrammar {
    import URIParserUtil.{ encodeNonASCII, decodePercentEncode, pathReservedChars, paramNameReservedChars, paramValueReservedChars, fragmentReservedChars }

    override def normalizePathComponent(component: String) = encodeNonASCII(decodePercentEncode(component), pathReservedChars)
    override def normalizeParams(params: Seq[Param]): Seq[Param] = params
    override def normalizeParamName(name: String) = encodeNonASCII(decodePercentEncode(name.replace('+', ' ')), paramNameReservedChars).replace(' ', '+')
    override def normalizeParamValue(value: String) = encodeNonASCII(decodePercentEncode(value.replace('+', ' ')), paramValueReservedChars).replace(' ', '+')
    override def normalizeFragment(fragment: String) = encodeNonASCII(decodePercentEncode(fragment), fragmentReservedChars)
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

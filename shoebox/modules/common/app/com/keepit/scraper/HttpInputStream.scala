package com.keepit.scraper

import java.io.FilterInputStream
import java.io.InputStream

class HttpInputStream(input: InputStream) extends FilterInputStream(input) {

  var httpContentType: Option[String] = None

  def setContentType(contentType: String) {
    httpContentType = Some(contentType)
  }
  def getContentType() = httpContentType
}

package com.keepit.rover.extractor.tika

import com.keepit.common.net.URI

object URITokenizer {

  private[this] val specialCharRegex = """[/\.:#&+~_-]+""".r

  def getTokens(uri: URI): Seq[String] = {
    uri.path match {
      case Some(path) => specialCharRegex.split(path).filter { _.length > 0 }
      case _ => Seq()
    }
  }

  def getTokens(url: String): Seq[String] = {
    URI.safelyParse(url).map(getTokens) getOrElse Seq.empty
  }
}


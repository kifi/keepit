package com.keepit.scraper.extractor

import com.keepit.common.net.URI
import scala.util.Success

object URITokenizer {

  private[this] val specialCharRegex = """[/\.:#&+~_-]+""".r

  def getTokens(uriString: String): Seq[String] = {
    URI.parse(uriString) match {
      case Success(uri) =>
        uri.path match {
          case Some(path) => specialCharRegex.split(path).filter { _.length > 0 }
          case _ => Seq()
        }
      case _ => Seq()
    }
  }
}


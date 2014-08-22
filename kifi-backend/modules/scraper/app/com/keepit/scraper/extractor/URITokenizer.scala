package com.keepit.scraper.extractor

import com.keepit.common.net.URI
import scala.util.Success

object URITokenizer {

  private[this] val specialCharRegex = """[/\.:#&+~_-]+""".r

  def getTokens(uri: URI): Seq[String] = {
    uri.path match {
      case Some(path) => specialCharRegex.split(path).filter { _.length > 0 }
      case _ => Seq()
    }
  }
}


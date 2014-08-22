package com.keepit.scraper.extractor

import com.keepit.common.logging.Logging
import com.keepit.scraper.ScraperConfig
import org.jsoup.nodes.Document
import com.keepit.common.net.URI
// import com.keepit.normalizer.LinkedInNormalizer

object LinkedInExtractorProvider extends ExtractorProvider {
  def isDefinedAt(uri: URI) = false //LinkedInNormalizer.linkedInCanonicalPublicProfile.findFirstIn(uri.toString).isDefined // TODO
  def apply(uri: URI) = new LinkedInExtractor(uri, ScraperConfig.maxContentChars)
}

class LinkedInExtractor(publicProfileUrl: URI, maxContentChars: Int) extends JsoupBasedExtractor(publicProfileUrl, maxContentChars) with Logging {
  val idExtractor = new LinkedInIdExtractor(publicProfileUrl, maxContentChars)

  def parse(doc: Document) = {

    val title = doc.getElementById("member-1").text
    val overview = doc.select("[id=overview] dd").text
    val sections = doc.select("[id^=profile-] .content").text
    val id = idExtractor.parse(doc)
    Seq(title, overview, sections, id).filter(_.nonEmpty).mkString("\n")
  }
}

class LinkedInIdExtractor(publicProfileUrl: URI, maxContentChars: Int) extends JsoupBasedExtractor(publicProfileUrl, maxContentChars) with Logging {

  def parse(doc: Document): String = {
    val idPattern = """newTrkInfo = '([0-9]{1,20}),' \+ document.referrer.substr\(0\,128\)""".r
    idPattern.findFirstMatchIn(doc.getElementsByTag("script").toString) match {
      case Some(idPattern(id)) => id
      case None => ""
    }
  }
}

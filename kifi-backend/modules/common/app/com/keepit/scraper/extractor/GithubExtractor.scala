package com.keepit.scraper.extractor

import com.keepit.common.logging.Logging
import com.keepit.common.net.Host
import com.keepit.common.net.URI
import com.keepit.scraper.Scraper
import org.apache.tika.sax.ContentHandlerDecorator
import org.apache.tika.parser.html.DefaultHtmlMapper
import org.xml.sax.Attributes
import org.xml.sax.ContentHandler
import org.jsoup.nodes.Document

object GithubExtractorFactory extends ExtractorFactory {
  def isDefinedAt(uri: URI) = {
    uri match {
      case URI(_, _, Some(Host("com", "github", _*)), _, Some(path), Some(query), _) =>
        true
      case _ => false
    }
  }
  def apply(uri: URI) = new GithubExtractor(uri.toString, Scraper.maxContentChars)
}

class GithubExtractor(url: String, maxContentChars: Int) extends JsoupBasedExtractor(url, maxContentChars) with Logging {

  def parse(doc: Document) = {
    // Determine which kind of page we're on
    val selectors =
      if (url.matches(".*/issues/.*")) {
        // Issue
        Set("h1.entry-title", ".discussion-topic-header", ".markdown-format", ".email-format")
      } else if (url.matches(".*/issues")) {
        // Issues list
        Set("h1.entry-title", ".issues-list")
      } else if (url.matches(".*/wiki.*")) {
        // Wiki
        Set("h1.entry-title", "#wiki-wrapper")
      } else if (url.matches(".*/pull/.*")) {
        // Pull request
        Set("h1.entry-title", ".pull-description", ".discussion-topic-header", ".markdown-format", ".email-format")
      } else if (url.matches(".*/pulls")) {
        // Pull requests
        Set("h1.entry-title", ".pulls-list")
      } else if(doc.select("body.page-profile").first() != null) {
        // Profile
        Set(".profilecols .vcard", ".contributions-tab .popular-repos")
      }  else if (doc.select("body.page-blob").first() != null) {
        // Source page
        Set(".breadcrumb", ".frame-meta .commit", ".lines .highlight")
      } else if (doc.select(".gist .data").first() != null) {
        // Gist
        Set(".meta .vcard", ".meta .info", ".data .line_data", "#comments")
      } else if (doc.select(".repo-desc-homepage").first() != null) {
        // Repo main page
        Set("h1.entry-title", ".repo-desc-homepage", "#readme")
      } else {
        // Not sure. Be generous, grab common elements
        Set("h1.entry-title", ".markdown-format", ".email-format", "#readme", ".meta .info")
      }
    selectors.map(doc.select(_).text).mkString("\n")
  }
}

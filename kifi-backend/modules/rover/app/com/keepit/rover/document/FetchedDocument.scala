package com.keepit.rover.document

import com.keepit.rover.article.content.PageAuthor
import com.keepit.rover.article.content.NormalizationInfo
import com.keepit.rover.document.tika.KeywordValidator
import com.keepit.rover.document.utils.DateTimeMetadataParser
import org.joda.time.DateTime

trait FetchedDocument {
  def getMetadata(name: String): Option[String]
  def getLinks(rel: String): Set[String]
  def getTitle: Option[String]
  def getDescription: Option[String] = getMetadata("description")
  def getAuthor: Option[PageAuthor] = getMetadata("author").map(PageAuthor(_, None))
  def getMetaKeywords: Seq[String] = getMetadata("keywords").map(meta => KeywordValidator.specialRegex.split(meta).filter { _.length > 0 }.toSeq) getOrElse Seq.empty
  def getPublishedAt: Option[DateTime] = {
    Stream("article:published_time", "article:published", "ptime", "pdate").map(getMetadata).flatten.headOption.flatMap(DateTimeMetadataParser.parse)
  }

  def getOpenGraphType: Option[String] = getMetadata("og:type")

  def getNormalizationInfo: NormalizationInfo = {
    NormalizationInfo(
      canonicalUrl = getCanonicalUrl,
      openGraphUrl = getOpenGraphUrl,
      alternateUrls = getAlternateUrls,
      shortUrl = getShortUrl
    )
  }

  private def getAlternateUrls: Set[String] = getLinks("alternate")
  private def getCanonicalUrl: Option[String] = getLinks("canonical").headOption
  private def getOpenGraphUrl: Option[String] = getMetadata("og:url")
  private def getShortUrl: Option[String] = getLinks("shortlink").headOption
}

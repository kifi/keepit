package com.keepit.rover.article

import com.keepit.model.PageAuthor
import com.keepit.scraper.HttpRedirect
import com.kifi.macros.json
import org.joda.time.DateTime

trait ArticleContent {
  def destinationUrl: String
  def title: Option[String]
  def description: Option[String]
  def content: Option[String]
  def keywords: Seq[String]
  def authors: Seq[PageAuthor]
  def publishedAt(): Option[DateTime]
}

trait HTTPContextHolder { self: ArticleContent =>
  def http: HTTPContext
}

@json
case class HTTPContext(
  status: Int,
  redirects: Seq[HttpRedirect],
  message: Option[String])

trait NormalizationContextHolder { self: ArticleContent =>
  def normalization: NormalizationContext
}

@json
case class NormalizationContext(
  canonicalUrl: Option[String],
  openGraphUrl: Option[String],
  alternateUrls: Set[String],
  shortUrl: Option[String])
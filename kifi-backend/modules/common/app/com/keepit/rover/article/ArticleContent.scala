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
  def mediaType: Option[String]
  def publishedAt(): Option[DateTime]
}

trait HttpInfoHolder { self: ArticleContent =>
  def http: HttpInfo
}

@json
case class HttpInfo(
  status: Int,
  redirects: Seq[HttpRedirect],
  message: Option[String],
  httpContentType: Option[String], // from http header
  httpOriginalContentCharset: Option[String] // from EntityUtils.getContentCharSet
  )

trait NormalizationInfoHolder { self: ArticleContent =>
  def normalization: NormalizationInfo
}

@json
case class NormalizationInfo(
  canonicalUrl: Option[String],
  openGraphUrl: Option[String],
  alternateUrls: Set[String],
  shortUrl: Option[String])
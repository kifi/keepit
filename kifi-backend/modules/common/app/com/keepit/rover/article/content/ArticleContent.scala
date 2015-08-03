package com.keepit.rover.article.content

import com.keepit.rover.article.Article
import com.keepit.rover.fetcher.HttpRedirect
import com.keepit.rover.model.HttpProxy
import com.kifi.macros.json
import org.joda.time.DateTime

trait ArticleContent[A <: Article] {
  def destinationUrl: String
  def title: Option[String]
  def description: Option[String]
  def content: Option[String]
  def keywords: Seq[String]
  def authors: Seq[PageAuthor]
  def contentType: Option[String]
  def publishedAt: Option[DateTime]
}

trait HttpInfoHolder {
  def http: HttpInfo
}

@json
case class HttpInfo(
  status: Int,
  message: Option[String],
  redirects: Seq[HttpRedirect],
  httpContentType: Option[String],
  httpOriginalContentCharset: Option[String])

object HttpInfo {

  def ok: HttpInfo = {
    HttpInfo(
      200,
      Some("OK"),
      Seq(),
      None,
      None
    )
  }

}

trait NormalizationInfoHolder {
  def normalization: NormalizationInfo
}

@json
case class NormalizationInfo(
  canonicalUrl: Option[String],
  openGraphUrl: Option[String],
  alternateUrls: Set[String],
  shortUrl: Option[String])

@json
case class PageAuthor(name: String, url: Option[String] = None)

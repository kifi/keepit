package com.keepit.rover.article.content

import com.keepit.model.PageAuthor
import com.keepit.rover.article.Article
import com.keepit.rover.document.utils.{ Signature, SignatureBuilder }
import com.keepit.rover.fetcher.HttpRedirect
import com.kifi.macros.json
import org.joda.time.DateTime

trait ArticleContent[A <: Article] {
  def destinationUrl: String
  def title: Option[String]
  def description: Option[String]
  def content: Option[String]
  def keywords: Seq[String]
  def authors: Seq[PageAuthor]
  def mediaType: Option[String]
  def publishedAt: Option[DateTime]
}

object ArticleContent {
  def defaultSignature[A <: Article](articleContent: ArticleContent[A]): Signature = {
    new SignatureBuilder().add(
      Seq(
        articleContent.title.toSeq,
        articleContent.description.toSeq,
        articleContent.content.toSeq,
        articleContent.keywords,
        articleContent.authors.map(_.name)
      ).flatten
    ).build
  }
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

trait NormalizationInfoHolder {
  def normalization: NormalizationInfo
}

@json
case class NormalizationInfo(
  canonicalUrl: Option[String],
  openGraphUrl: Option[String],
  alternateUrls: Set[String],
  shortUrl: Option[String])
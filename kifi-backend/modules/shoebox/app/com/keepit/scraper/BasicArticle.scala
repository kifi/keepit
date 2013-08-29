package com.keepit.scraper

import com.keepit.search.Article

case class BasicArticle(
  title: String,
  content: String,
  description: Option[String] = None,
  media: Option[String] = None,
  httpContentType: Option[String] = None, // from http header
  httpOriginalContentCharset: Option[String] = None, // from EntityUtils.getContentCharSet
  destinationUrl: Option[String] = None
)

object BasicArticle {
  def apply(article: Article): BasicArticle = BasicArticle(
    title = article.title,
    content = article.content,
    description = article.description,
    media = article.media,
    httpContentType = article.httpContentType,
    httpOriginalContentCharset = article.httpOriginalContentCharset,
    destinationUrl = article.destinationUrl
  )
}

package com.keepit.scraper

import com.keepit.rover.article.Signature
import com.keepit.rover.fetcher.HttpRedirect
import com.keepit.search.Article
import com.keepit.model.NormalizedURI

sealed abstract class ScraperResult

case class Scraped(article: Article, signature: Signature, redirects: Seq[HttpRedirect]) extends ScraperResult
case class NotScrapable(destinationUrl: Option[String], redirects: Seq[HttpRedirect]) extends ScraperResult
case class Error(httpStatusCode: Int, msg: String) extends ScraperResult
case object NotModified extends ScraperResult

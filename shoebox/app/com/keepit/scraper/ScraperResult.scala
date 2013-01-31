package com.keepit.scraper

import com.keepit.search.Article
import com.keepit.model.NormalizedURI

sealed abstract class ScraperResult

case class Scraped(article: Article) extends ScraperResult
case class Error(httpStatusCode: Int, msg: String) extends ScraperResult
case object NotModified extends ScraperResult

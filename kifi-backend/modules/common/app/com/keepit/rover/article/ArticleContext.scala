package com.keepit.rover.article

import com.keepit.scraper.HttpRedirect
import com.kifi.macros.json

trait ArticleContext {
  def destinationUrl: String
}

trait HTTPContextProvider { self: ArticleContext =>
  def http: HTTPContext
}

@json
case class HTTPContext(
  status: Int,
  redirects: Seq[HttpRedirect],
  message: Option[String])

trait NormalizationContextProvider { self: ArticleContext =>
  def normalization: NormalizationContext
}

@json
case class NormalizationContext(
  canonicalUrl: Option[String],
  openGraphUrl: Option[String],
  alternateUrls: Set[String],
  shortUrl: Option[String])

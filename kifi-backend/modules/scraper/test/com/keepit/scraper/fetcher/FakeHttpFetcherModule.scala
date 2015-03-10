package com.keepit.scraper.fetcher

import com.google.inject.{ Provides, Singleton }
import com.keepit.rover.fetcher.{ DeprecatedHttpFetchStatus, DeprecatedHttpFetcher }

case class FakeHttpFetcherModule(urlToResponse: Option[PartialFunction[String, DeprecatedHttpFetchStatus]] = None) extends HttpFetcherModule {

  def configure(): Unit = {}

  @Singleton
  @Provides
  def httpFetcher(): DeprecatedHttpFetcher = {
    new FakeDeprecatedHttpFetcher(urlToResponse)
  }
}

package com.keepit.scraper.fetcher

import com.google.inject.{ Provides, Singleton }

case class FakeDeprecatedHttpFetcherModule(urlToResponse: Option[PartialFunction[String, DeprecatedHttpFetchStatus]] = None) extends DeprecatedHttpFetcherModule {

  def configure(): Unit = {}

  @Singleton
  @Provides
  def httpFetcher(): DeprecatedHttpFetcher = {
    new FakeDeprecatedHttpFetcher(urlToResponse)
  }
}

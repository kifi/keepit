package com.keepit.scraper.fetcher

import com.google.inject.{ Provides, Singleton }

case class FakeHttpFetcherModule(urlToResponse: Option[PartialFunction[String, HttpFetchStatus]] = None) extends HttpFetcherModule {

  def configure(): Unit = {}

  @Singleton
  @Provides
  def httpFetcher(): HttpFetcher = {
    new FakeHttpFetcher(urlToResponse)
  }
}

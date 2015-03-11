package com.keepit.scraper.fetcher

import net.codingwell.scalaguice.ScalaModule

trait DeprecatedHttpFetcherModule extends ScalaModule

case class DeprecatedHttpFetcherImplModule() extends DeprecatedHttpFetcherModule {
  def configure() = {
    bind[DeprecatedHttpFetcher].to[DeprecatedHttpFetcherImpl]
  }
}

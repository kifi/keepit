package com.keepit.scraper

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.google.inject.{Provides, Singleton}

trait ScraperModule extends ScalaModule

case class ScraperImplModule() extends ScraperModule {

  def configure {
    bind[ScraperPlugin].to[ScraperPluginImpl].in[AppScoped]
    bind[DataIntegrityPlugin].to[DataIntegrityPluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def scraperConfig: ScraperConfig = ScraperConfig()

  @Singleton
  @Provides
  def httpFetcher: HttpFetcher = {
    new HttpFetcherImpl(
      userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_2) AppleWebKit/537.17 (KHTML, like Gecko) Chrome/24.0.1309.0 Safari/537.17",
      connectionTimeout = 30000,
      soTimeOut = 30000,
      trustBlindly = true
    )
  }
}

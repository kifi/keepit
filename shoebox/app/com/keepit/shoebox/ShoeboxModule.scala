package com.keepit.shoebox

import com.google.common.io.Files
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.multibindings.Multibinder
import com.keepit.classify.DomainTagImportSettings
import com.keepit.common.analytics.reports._
import com.keepit.common.analytics.{SliderShownListener, UsefulPageListener, KifiResultClickedListener, EventListenerPlugin}
import com.keepit.common.logging.Logging
import com.keepit.common.social.{SocialGraphRefresherImpl, SocialGraphRefresher}
import com.keepit.inject.AppScoped
import com.keepit.scraper._
import com.tzavellas.sse.guice.ScalaModule

class ShoeboxModule() extends ScalaModule with Logging {
  def configure(): Unit = {
    println("configuring ShoeboxModule")
    bind[SocialGraphRefresher].to[SocialGraphRefresherImpl].in[AppScoped]
    bind[ReportBuilderPlugin].to[ReportBuilderPluginImpl].in[AppScoped]
    bind[DataIntegrityPlugin].to[DataIntegrityPluginImpl].in[AppScoped]
    bind[ScraperPlugin].to[ScraperPluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def domainTagImportSettings: DomainTagImportSettings = {
    val dirPath = Files.createTempDir().getAbsolutePath
    DomainTagImportSettings(localDir = dirPath, url = "http://www.komodia.com/clients/42.zip")
  }

}

package com.keepit.shoebox

import com.google.common.io.Files
import com.google.inject.{Provider, Provides, Singleton}
import com.keepit.classify.DomainTagImportSettings
import com.keepit.common.analytics.reports._
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{MailToKeepPlugin, MailToKeepPluginImpl, MailToKeepServerSettings}
import com.keepit.common.social.{SocialGraphPluginImpl, SocialGraphPlugin, SocialGraphRefresherImpl, SocialGraphRefresher}
import com.keepit.inject.AppScoped
import com.keepit.scraper._
import com.tzavellas.sse.guice.ScalaModule

import play.api.Play.current

class ShoeboxModule() extends ScalaModule with Logging {
  def configure() {
    println("configuring ShoeboxModule")
    bind[SocialGraphRefresher].to[SocialGraphRefresherImpl].in[AppScoped]
    bind[ReportBuilderPlugin].to[ReportBuilderPluginImpl].in[AppScoped]
    bind[DataIntegrityPlugin].to[DataIntegrityPluginImpl].in[AppScoped]
    bind[ScraperPlugin].to[ScraperPluginImpl].in[AppScoped]
    bind[MailToKeepPlugin].to[MailToKeepPluginImpl].in[AppScoped]
    bind[SocialGraphPlugin].to[SocialGraphPluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def domainTagImportSettings: DomainTagImportSettings = {
    val dirPath = Files.createTempDir().getAbsolutePath
    DomainTagImportSettings(localDir = dirPath, url = "http://www.komodia.com/clients/42.zip")
  }

  @Singleton
  @Provides
  def mailToKeepServerSettings: MailToKeepServerSettings = {
    val username = current.configuration.getString("mailtokeep.username").get
    val password = current.configuration.getString("mailtokeep.password").get
    val server = current.configuration.getString("mailtokeep.server").getOrElse("imap.gmail.com")
    val protocol = current.configuration.getString("mailtokeep.protocol").getOrElse("imaps")
    MailToKeepServerSettings(username = username, password = password, server = server, protocol = protocol)
  }

}

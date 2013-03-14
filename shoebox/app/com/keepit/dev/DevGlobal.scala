package com.keepit.dev

import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Stage
import com.google.inject.util.Modules
import com.keepit.FortyTwoGlobal
import com.keepit.common.analytics.reports.ReportBuilderPlugin
import com.keepit.common.cache.FortyTwoCachePlugin
import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.controller.ServiceType
import com.keepit.common.mail.MailSenderPlugin
import com.keepit.common.social.SocialGraphRefresher
import com.keepit.inject._
import com.keepit.scraper._
import com.keepit.search.index.ArticleIndexerPlugin
import com.keepit.shoebox.ShoeboxModule
import play.api.Application
import play.api.Mode._
import play.api.Play.current
import com.keepit.search.SearchModule

object DevGlobal extends FortyTwoGlobal(Dev) {

  override lazy val injector: Injector = Guice.createInjector(Stage.DEVELOPMENT, Modules.`override`(
    Modules.`override`(new ShoeboxModule()).`with`(new SearchModule())
  ).`with`(new DevModule()))

  override def onStart(app: Application): Unit = {
    require(inject[FortyTwoServices].currentService == ServiceType.DEV_MODE,
        "DevGlobal can only be run on a dev service")
    log.info("starting the shoebox")
    super.onStart(app)
    inject[ScraperPlugin].scrape()
    inject[ArticleIndexerPlugin].index()
    inject[SocialGraphRefresher]
    inject[MailSenderPlugin].processOutbox()
    inject[ReportBuilderPlugin].enabled
    inject[DataIntegrityPlugin].enabled
    inject[FortyTwoCachePlugin].enabled
    log.info("shoebox started")
  }
}

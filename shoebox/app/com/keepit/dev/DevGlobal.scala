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
import com.keepit.module.CommonModule
import com.keepit.scraper.{DataIntegrityPlugin, ScraperPlugin}
import com.keepit.search.{SearchGlobal, SearchModule}
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.search.index.ArticleIndexerPlugin
import com.keepit.shoebox.{ShoeboxGlobal, ShoeboxModule}
import play.api.Application
import play.api.Mode._
import play.api.Play.current

object DevGlobal extends FortyTwoGlobal(Dev) {

  val modules = Seq(Modules.`override`((ShoeboxGlobal.modules ++ SearchGlobal.modules): _*).`with`(new DevModule))

  override def onStart(app: Application): Unit = {
    require(inject[FortyTwoServices].currentService == ServiceType.DEV_MODE,
        "DevGlobal can only be run on a dev service")
    log.info("starting the shoebox")
    super.onStart(app)
    inject[ScraperPlugin].scrape()
    inject[SocialGraphRefresher]
    inject[ReportBuilderPlugin].enabled
    inject[DataIntegrityPlugin].enabled
    inject[MailSenderPlugin].processOutbox()
    inject[FortyTwoCachePlugin].enabled
    inject[ArticleIndexerPlugin].index()
    inject[URIGraphPlugin].enabled
    log.info("shoebox started")
  }
}

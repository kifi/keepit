package com.keepit.shoebox

import play.api._
import play.api.Mode._
import play.api.Play.current
import com.keepit.FortyTwoGlobal
import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.controller.ServiceType
import com.keepit.scraper.ScraperPlugin
import com.keepit.search.index.ArticleIndexerPlugin
import com.keepit.inject._
import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Stage
import com.keepit.common.social.SocialGraphPlugin
import com.keepit.common.mail.MailSenderPlugin
import com.keepit.common.healthcheck._
import com.keepit.common.analytics.PersistEventPlugin
import com.keepit.common.analytics.reports.ReportBuilderPlugin

object ShoeboxGlobal extends FortyTwoGlobal(Prod) {

  override lazy val injector: Injector = Guice.createInjector(Stage.PRODUCTION, new ShoeboxModule())

  override def onStart(app: Application): Unit = {
    log.info("starting the shoebox")
//    require(inject[FortyTwoServices].currentService == ServiceType.SHOEBOX,
//        "ShoeboxGlobal can only be run on a shoebox service")
    super.onStart(app)
    require(inject[ScraperPlugin].enabled)
    require(inject[ArticleIndexerPlugin].enabled)
    require(inject[SocialGraphPlugin].enabled)
    require(inject[MailSenderPlugin].enabled)
    inject[MailSenderPlugin].processOutbox()
    require(inject[HealthcheckPlugin].enabled)
    require(inject[PersistEventPlugin].enabled)
    require(inject[ReportBuilderPlugin].enabled)
    log.info("shoebox started")
  }

}

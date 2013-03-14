package com.keepit.shoebox

import play.api._
import play.api.Mode._
import play.api.Play.current
import com.keepit.FortyTwoGlobal
import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.controller.ServiceType
import com.keepit.scraper._
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
import com.keepit.common.cache.MemcachedPlugin
import com.keepit.search.graph.URIGraphPlugin

object ShoeboxGlobal extends FortyTwoGlobal(Prod) {
  private var creatingInjector = false
  override lazy val injector: Injector = {
    if (creatingInjector) throw new Exception("Injector is being created!")
    creatingInjector = true
    try {
      createInjector()
    } finally {
      creatingInjector = false
    }
  }

  def createInjector(): Injector = Guice.createInjector(Stage.PRODUCTION, new ShoeboxModule())

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
    require(inject[DataIntegrityPlugin].enabled)
    require(inject[MemcachedPlugin].enabled)
    require(inject[URIGraphPlugin].enabled)
    log.info("shoebox started")
  }

}

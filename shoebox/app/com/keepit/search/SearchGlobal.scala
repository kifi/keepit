package com.keepit.search

import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Stage
import com.keepit.FortyTwoGlobal
import com.keepit.common.analytics.PersistEventPlugin
import com.keepit.common.cache.MemcachedPlugin
import com.keepit.common.healthcheck._
import com.keepit.common.mail.MailSenderPlugin
import com.keepit.inject._
import com.keepit.scraper._
import com.keepit.search.index.ArticleIndexerPlugin

import play.api.Mode._
import play.api.Play.current
import play.api._

object SearchGlobal extends FortyTwoGlobal(Prod) {
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

  def createInjector(): Injector = Guice.createInjector(Stage.PRODUCTION, new SearchModule())

  override def onStart(app: Application): Unit = {
    log.info("starting the shoebox")
//    require(inject[FortyTwoServices].currentService == ServiceType.SHOEBOX,
//        "ShoeboxGlobal can only be run on a shoebox service")
    super.onStart(app)
    require(inject[ScraperPlugin].enabled)
    require(inject[ArticleIndexerPlugin].enabled)
    require(inject[MailSenderPlugin].enabled)
    inject[MailSenderPlugin].processOutbox()
    require(inject[HealthcheckPlugin].enabled)
    require(inject[PersistEventPlugin].enabled)
    require(inject[DataIntegrityPlugin].enabled)
    require(inject[MemcachedPlugin].enabled)
    log.info("shoebox started")
  }

}

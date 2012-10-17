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

object ShoeboxGlobal extends FortyTwoGlobal(Prod) {

  override lazy val injector: Injector = Guice.createInjector(Stage.PRODUCTION, ShoeboxModule())
  
  override def onStart(app: Application): Unit = {
    require(FortyTwoServices.currentService == ServiceType.SHOEBOX, 
        "ShoeboxGlobal can only be run on a shoebox service")
    log.info("starting the shoebox")
    super.onStart(app)
    inject[ScraperPlugin].scrape()
    inject[ArticleIndexerPlugin].index()
    log.info("shoebox started")
  }

}
package com.keepit.dev

import play.api.Mode._
import play.api.Application
import play.api.Play.current
import com.keepit.FortyTwoGlobal
import com.keepit.inject._
import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Stage
import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.controller.ServiceType
import com.keepit.scraper.ScraperPlugin
import com.keepit.search.index.ArticleIndexerPlugin
import com.keepit.common.social.SocialTokenRefresher
import com.keepit.common.mail.MailSenderPlugin

object DevGlobal extends FortyTwoGlobal(Dev) {

  override lazy val injector: Injector = Guice.createInjector(Stage.DEVELOPMENT, DevModule())

  override def onStart(app: Application): Unit = {
    require(FortyTwoServices.currentService == ServiceType.DEV_MODE,
        "DevGlobal can only be run on a dev service")
    log.info("starting the shoebox")
    super.onStart(app)
    inject[ScraperPlugin].scrape()
    inject[ArticleIndexerPlugin].index()
    inject[SocialTokenRefresher]
    inject[MailSenderPlugin].processOutbox()
    log.info("shoebox started")
  }
}

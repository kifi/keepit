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
import com.keepit.module.CommonModule
import com.keepit.search.index.ArticleIndexerPlugin
import play.api.Mode._
import play.api.Play.current
import play.api._

object SearchGlobal extends FortyTwoGlobal(Prod) {
  val modules = Seq(new CommonModule, new SearchModule)

  override def onStart(app: Application): Unit = {
    log.info("starting the shoebox")
    // TODO(greg): figure out how to do service type for search
    // require(inject[FortyTwoServices].currentService == ServiceType.SHOEBOX,
    //    "ShoeboxGlobal can only be run on a shoebox service")
    super.onStart(app)
    require(inject[ArticleIndexerPlugin].enabled)
    require(inject[MailSenderPlugin].enabled)
    inject[MailSenderPlugin].processOutbox()
    require(inject[HealthcheckPlugin].enabled)
    require(inject[PersistEventPlugin].enabled)
    require(inject[MemcachedPlugin].enabled)
    log.info("shoebox started")
  }

}

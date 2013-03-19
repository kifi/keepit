package com.keepit.shoebox

import com.keepit.FortyTwoGlobal
import com.keepit.common.analytics.PersistEventPlugin
import com.keepit.common.analytics.reports.ReportBuilderPlugin
import com.keepit.common.cache.MemcachedPlugin
import com.keepit.common.healthcheck._
import com.keepit.common.mail.MailSenderPlugin
import com.keepit.common.social.SocialGraphPlugin
import com.keepit.common.social.SocialGraphRefresher
import com.keepit.inject._
import com.keepit.module.CommonModule
import com.keepit.scraper._
import com.keepit.search.SearchModule
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.search.index.ArticleIndexerPlugin
import play.api.Mode._
import play.api.Play.current
import play.api._

object ShoeboxGlobal extends FortyTwoGlobal(Prod) {

  //TODO(greg): remove SearchModule once we've migrated search stuff to search server
  val modules = Seq(new CommonModule, new ShoeboxModule, new SearchModule)

  override def onStart(app: Application): Unit = {
    log.info("starting the shoebox")
//    require(inject[FortyTwoServices].currentService == ServiceType.SHOEBOX,
//        "ShoeboxGlobal can only be run on a shoebox service")
    super.onStart(app)
    require(inject[ScraperPlugin].enabled)
    require(inject[ArticleIndexerPlugin].enabled)
    require(inject[SocialGraphPlugin].enabled)
    require(inject[SocialGraphRefresher].enabled)
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

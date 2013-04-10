package com.keepit.shoebox

import com.keepit.FortyTwoGlobal
import com.keepit.common.analytics.PersistEventPlugin
import com.keepit.common.analytics.reports.ReportBuilderPlugin
import com.keepit.common.cache.FortyTwoCachePlugin
import com.keepit.common.healthcheck._
import com.keepit.common.mail.{MailToKeepPlugin, MailSenderPlugin}
import com.keepit.common.social.SocialGraphPlugin
import com.keepit.common.social.SocialGraphRefresher
import com.keepit.inject._
import com.keepit.module.CommonModule
import com.keepit.scraper._
import play.api.Mode._
import play.api.Play.current
import play.api._

object ShoeboxGlobal extends FortyTwoGlobal(Prod) {

  val modules = Seq(new CommonModule, new ShoeboxModule)

  override def onStart(app: Application): Unit = {
    log.info("starting the shoebox")
    startServices()
    super.onStart(app)
    log.info("shoebox started")
  }

  def startServices() {
    require(injector.inject[ScraperPlugin].enabled)
    require(injector.inject[SocialGraphPlugin].enabled)
    require(injector.inject[SocialGraphRefresher].enabled)
    require(injector.inject[MailSenderPlugin].enabled)
    injector.inject[MailSenderPlugin].processOutbox()
    require(injector.inject[MailToKeepPlugin].enabled)
    require(injector.inject[HealthcheckPlugin].enabled)
    require(injector.inject[PersistEventPlugin].enabled)
    require(injector.inject[ReportBuilderPlugin].enabled)
    require(injector.inject[DataIntegrityPlugin].enabled)
    require(injector.inject[FortyTwoCachePlugin].enabled)
  }
}

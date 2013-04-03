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
    super.onStart(app)
    startServices()
    log.info("shoebox started")
  }

  def startServices() {
    require(inject[ScraperPlugin].enabled)
    require(inject[SocialGraphPlugin].enabled)
    require(inject[SocialGraphRefresher].enabled)
    require(inject[MailSenderPlugin].enabled)
    inject[MailSenderPlugin].processOutbox()
    require(inject[MailToKeepPlugin].enabled)
    require(inject[HealthcheckPlugin].enabled)
    require(inject[PersistEventPlugin].enabled)
    require(inject[ReportBuilderPlugin].enabled)
    require(inject[DataIntegrityPlugin].enabled)
    require(inject[FortyTwoCachePlugin].enabled)
  }
}

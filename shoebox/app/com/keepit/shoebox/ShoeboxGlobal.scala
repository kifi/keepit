package com.keepit.shoebox

import com.keepit.FortyTwoGlobal
import com.keepit.common.analytics.PersistEventPlugin
import com.keepit.common.analytics.reports.ReportBuilderPlugin
import com.keepit.common.cache.FortyTwoCachePlugin
import com.keepit.common.healthcheck._
import com.keepit.common.mail.{InvitationMailPlugin, MailToKeepPlugin, MailSenderPlugin}
import com.keepit.common.social.SocialGraphPlugin
import com.keepit.common.social.SocialGraphRefresher
import com.keepit.common.store.ImageDataIntegrityPlugin
import com.keepit.module.CommonModule
import com.keepit.realtime.UserEmailNotifierPlugin
import com.keepit.scraper._

import play.api.Mode._
import play.api._

object ShoeboxGlobal extends FortyTwoGlobal(Prod) with ShoeboxServices {

  val modules = Seq(new CommonModule, new ShoeboxModule)

  override def onStart(app: Application): Unit = {
    log.info("starting the shoebox")
    startShoeboxServices()
    super.onStart(app)
    log.info("shoebox started")
  }
}

trait ShoeboxServices { self: FortyTwoGlobal =>
  def startShoeboxServices() {
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
    require(injector.inject[UserEmailNotifierPlugin].enabled)
    require(injector.inject[ImageDataIntegrityPlugin].enabled)
    require(injector.inject[InvitationMailPlugin].enabled)
  }
}

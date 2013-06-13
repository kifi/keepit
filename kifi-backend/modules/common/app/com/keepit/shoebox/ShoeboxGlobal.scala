package com.keepit.shoebox

import com.keepit.FortyTwoGlobal
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
    require(injector.instance[ScraperPlugin].enabled)
    require(injector.instance[SocialGraphPlugin].enabled)
    require(injector.instance[SocialGraphRefresher].enabled)
    require(injector.instance[MailSenderPlugin].enabled)
    injector.instance[MailSenderPlugin].processOutbox()
    require(injector.instance[MailToKeepPlugin].enabled)
    require(injector.instance[HealthcheckPlugin].enabled)
    require(injector.instance[ReportBuilderPlugin].enabled)
    require(injector.instance[DataIntegrityPlugin].enabled)
    require(injector.instance[FortyTwoCachePlugin].enabled)
    require(injector.instance[UserEmailNotifierPlugin].enabled)
    require(injector.instance[ImageDataIntegrityPlugin].enabled)
    require(injector.instance[InvitationMailPlugin].enabled)
  }
}

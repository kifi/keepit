package com.keepit.shoebox

import com.keepit.reports._
import com.keepit.FortyTwoGlobal
import com.keepit.common.cache.FortyTwoCachePlugin
import com.keepit.common.healthcheck._
import com.keepit.common.mail.{MailToKeepPlugin, MailSenderPlugin}
import com.keepit.common.store.ImageDataIntegrityPlugin
import com.keepit.scraper._
import play.api.Mode._
import play.api._
import com.keepit.social.SocialGraphPlugin
import com.keepit.integrity.{UriIntegrityPlugin, DataIntegrityPlugin}
import com.keepit.common.integration.AutogenReaperPlugin

object ShoeboxGlobal extends FortyTwoGlobal(Prod) with ShoeboxServices {

  val module = ShoeboxProdModule()

  override def onStart(app: Application): Unit = {
    log.info("starting the shoebox")
    startShoeboxServices()
    super.onStart(app)
    log.info("shoebox started")
  }
}

trait ShoeboxServices { self: FortyTwoGlobal =>
  def startShoeboxServices() {
    require(injector.instance[ScrapeSchedulerPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[SocialGraphPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[MailSenderPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[AutogenReaperPlugin] != null) //make sure its not lazy loaded
    injector.instance[MailSenderPlugin].processOutbox()
    require(injector.instance[MailToKeepPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[HealthcheckPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[DataIntegrityPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[UriIntegrityPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[FortyTwoCachePlugin] != null) //make sure its not lazy loaded
    require(injector.instance[ImageDataIntegrityPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[GeckoboardReporterPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[UriIntegrityPlugin] != null) //make sure its not lazy loaded
  }
}

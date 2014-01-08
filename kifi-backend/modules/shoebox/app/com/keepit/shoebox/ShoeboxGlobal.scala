package com.keepit.shoebox

import com.keepit.reports._
import com.keepit.FortyTwoGlobal
import com.keepit.common.cache.FortyTwoCachePlugin
import com.keepit.common.healthcheck._
import com.keepit.common.mail.{InvitationMailPlugin, MailToKeepPlugin, MailSenderPlugin}
import com.keepit.common.social.SocialGraphRefresher
import com.keepit.common.store.ImageDataIntegrityPlugin
import com.keepit.scraper._
import play.api.Mode._
import play.api._
import com.keepit.learning.topicmodel.TopicUpdaterPlugin
import com.keepit.social.SocialGraphPlugin
import com.keepit.controllers.internal.ExpertRecommenderController
import com.keepit.learning.topicmodel.TopicModelSwitcherPlugin
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
    require(injector.instance[ScrapeSchedulerPlugin].enabled)
    require(injector.instance[SocialGraphPlugin].enabled)
    require(injector.instance[SocialGraphRefresher].enabled)
    require(injector.instance[MailSenderPlugin].enabled)
    require(injector.instance[AutogenReaperPlugin].enabled)
    injector.instance[MailSenderPlugin].processOutbox()
    require(injector.instance[MailToKeepPlugin].enabled)
    require(injector.instance[HealthcheckPlugin].enabled)
    require(injector.instance[DataIntegrityPlugin].enabled)
    require(injector.instance[UriIntegrityPlugin].enabled)
    require(injector.instance[FortyTwoCachePlugin].enabled)
    require(injector.instance[ImageDataIntegrityPlugin].enabled)
    require(injector.instance[InvitationMailPlugin].enabled)
    require(injector.instance[TopicUpdaterPlugin].enabled)
    require(injector.instance[TopicModelSwitcherPlugin].enabled)
    require(injector.instance[ExpertRecommenderController].enabled)
    require(injector.instance[GeckoboardReporterPlugin].enabled)
    require(injector.instance[UriIntegrityPlugin].enabled)
  }
}

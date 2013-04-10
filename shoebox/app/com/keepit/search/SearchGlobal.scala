package com.keepit.search

import com.keepit.FortyTwoGlobal
import com.keepit.common.analytics.PersistEventPlugin
import com.keepit.common.cache.FortyTwoCachePlugin
import com.keepit.common.healthcheck._
import com.keepit.common.mail.MailSenderPlugin
import com.keepit.inject._
import com.keepit.module.CommonModule
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.search.index.ArticleIndexerPlugin
import play.api.Mode._
import play.api.Play.current
import play.api._

object SearchGlobal extends FortyTwoGlobal(Prod) {
  val modules = Seq(new CommonModule, new SearchModule)

  override def onStart(app: Application) {
    log.info("starting the search")
    startServices()
    super.onStart(app)
    log.info("search started")
  }

  def startServices() {
    require(injector.inject[ArticleIndexerPlugin].enabled)
    require(injector.inject[URIGraphPlugin].enabled)
    require(injector.inject[MailSenderPlugin].enabled)
    injector.inject[MailSenderPlugin].processOutbox()
    require(injector.inject[HealthcheckPlugin].enabled)
    require(injector.inject[PersistEventPlugin].enabled)
    require(injector.inject[FortyTwoCachePlugin].enabled)
  }

}

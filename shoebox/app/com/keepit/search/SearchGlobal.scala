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
    super.onStart(app)
    startServices()
    log.info("search started")
  }

  def startServices() {
    require(inject[ArticleIndexerPlugin].enabled)
    require(inject[URIGraphPlugin].enabled)
    require(inject[MailSenderPlugin].enabled)
    inject[MailSenderPlugin].processOutbox()
    require(inject[HealthcheckPlugin].enabled)
    require(inject[PersistEventPlugin].enabled)
    require(inject[FortyTwoCachePlugin].enabled)
  }

}

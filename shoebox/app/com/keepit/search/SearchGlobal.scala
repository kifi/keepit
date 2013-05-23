package com.keepit.search

import com.keepit.FortyTwoGlobal
import com.keepit.common.analytics.PersistEventPlugin
import com.keepit.common.cache.FortyTwoCachePlugin
import com.keepit.common.healthcheck._
import com.keepit.common.mail.MailSenderPlugin
import com.keepit.module.CommonModule
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.search.index.ArticleIndexerPlugin
import play.api.Mode._
import play.api._
import com.keepit.search.nlp.NlpParser

object SearchGlobal extends FortyTwoGlobal(Prod) with SearchServices {
  val modules = Seq(new CommonModule, new SearchModule)

  override def onStart(app: Application) {
    log.info("starting the search")
    startSearchServices()
    super.onStart(app)
    log.info("search started")
  }

}

trait SearchServices { self: FortyTwoGlobal =>
  def startSearchServices() {
    require(injector.inject[ArticleIndexerPlugin].enabled)
    require(injector.inject[URIGraphPlugin].enabled)
    require(injector.inject[HealthcheckPlugin].enabled)
    require(injector.inject[FortyTwoCachePlugin].enabled)
    require(NlpParser.enabled)
  }
}

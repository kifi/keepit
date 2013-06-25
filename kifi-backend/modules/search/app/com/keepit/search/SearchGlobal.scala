package com.keepit.search

import com.keepit.FortyTwoGlobal
import com.keepit.common.cache.{InMemoryCachePlugin, FortyTwoCachePlugin}
import com.keepit.common.healthcheck._
import com.keepit.module.CommonModule
import com.keepit.search.comment.CommentIndexerPlugin
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.search.index.ArticleIndexerPlugin
import play.api.Mode._
import play.api._
import com.keepit.search.nlp.NlpParser

object SearchGlobal extends FortyTwoGlobal(Prod) with SearchServices {
  val modules = Seq(new CommonModule, new SearchModule, new SearchExclusiveModule)

  override def onStart(app: Application) {
    log.info("starting the search")
    startSearchServices()
    super.onStart(app)
    log.info("search started")
  }

}

trait SearchServices { self: FortyTwoGlobal =>
  def startSearchServices() {
    require(injector.instance[ArticleIndexerPlugin].enabled)
    require(injector.instance[URIGraphPlugin].enabled)
    require(injector.instance[CommentIndexerPlugin].enabled)
    require(injector.instance[HealthcheckPlugin].enabled)
    require(injector.instance[FortyTwoCachePlugin].enabled)
    require(injector.instance[InMemoryCachePlugin].enabled)
    require(NlpParser.enabled)
  }
}

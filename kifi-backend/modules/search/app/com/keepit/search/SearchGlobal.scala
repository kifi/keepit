package com.keepit.search

import com.keepit.FortyTwoGlobal
import com.keepit.common.cache.{InMemoryCachePlugin, FortyTwoCachePlugin}
import com.keepit.common.healthcheck._
import com.keepit.search.message.MessageIndexerPlugin
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.search.index.ArticleIndexerPlugin
import play.api.Mode._
import play.api._
import com.keepit.search.nlp.NlpParser
import com.keepit.search.user.UserIndexerPlugin
import com.keepit.search.phrasedetector.PhraseIndexerPlugin
import com.keepit.search.spellcheck.SpellIndexerPlugin

object SearchGlobal extends FortyTwoGlobal(Prod) with SearchServices {
  val module = SearchProdModule()

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
    require(injector.instance[MessageIndexerPlugin].enabled)
    require(injector.instance[UserIndexerPlugin].enabled)
    require(injector.instance[HealthcheckPlugin].enabled)
    require(injector.instance[FortyTwoCachePlugin].enabled)
    require(injector.instance[InMemoryCachePlugin].enabled)
    require(injector.instance[PhraseIndexerPlugin].enabled)
    require(injector.instance[SpellIndexerPlugin].enabled)
    require(NlpParser.enabled)
  }
}

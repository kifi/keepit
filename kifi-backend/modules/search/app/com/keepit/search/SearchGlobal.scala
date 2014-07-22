package com.keepit.search

import com.keepit.FortyTwoGlobal
import com.keepit.common.cache.{ InMemoryCachePlugin, FortyTwoCachePlugin }
import com.keepit.common.healthcheck._
import com.keepit.search.message.MessageIndexerPlugin
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.search.article.ArticleIndexerPlugin
import play.api.Mode._
import play.api._
import com.keepit.search.nlp.NlpParser
import com.keepit.search.user.UserIndexerPlugin
import com.keepit.search.phrasedetector.PhraseIndexerPlugin
import com.keepit.search.spellcheck.SpellIndexerPlugin
import com.keepit.search.graph.collection.CollectionGraphPlugin
import com.keepit.search.graph.user._
import com.keepit.search.graph.library.LibraryIndexerPlugin

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
    require(injector.instance[ArticleIndexerPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[URIGraphPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[CollectionGraphPlugin] != null)
    require(injector.instance[MessageIndexerPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[UserIndexerPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[HealthcheckPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[FortyTwoCachePlugin] != null) //make sure its not lazy loaded
    require(injector.instance[InMemoryCachePlugin] != null) //make sure its not lazy loaded
    require(injector.instance[PhraseIndexerPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[SpellIndexerPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[UserGraphPlugin] != null)
    require(injector.instance[SearchFriendGraphPlugin] != null)
    require(injector.instance[LoadBalancerCheckPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[LibraryIndexerPlugin] != null) //make sure its not lazy loaded
    require(NlpParser.enabled)
  }
}

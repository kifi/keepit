package com.keepit.search

import com.keepit.FortyTwoGlobal
import com.keepit.common.cache.{ InMemoryCachePlugin, FortyTwoCachePlugin }
import com.keepit.common.healthcheck._
import com.keepit.search.index.graph.keep.KeepIndexerPlugin
import com.keepit.search.index.graph.library.membership.LibraryMembershipIndexerPlugin
import com.keepit.search.index.message.MessageIndexerPlugin
import com.keepit.search.index.article.DeprecatedArticleIndexerPlugin
import play.api.Mode._
import play.api._
import com.keepit.search.index.user.UserIndexerPlugin
import com.keepit.search.index.phrase.PhraseIndexerPlugin
import com.keepit.search.index.graph.collection.CollectionGraphPlugin
import com.keepit.search.index.graph.user._
import com.keepit.search.index.graph.library.LibraryIndexerPlugin
import net.codingwell.scalaguice.InjectorExtensions._

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
    require(injector.instance[DeprecatedArticleIndexerPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[CollectionGraphPlugin] != null)
    require(injector.instance[MessageIndexerPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[UserIndexerPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[HealthcheckPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[FortyTwoCachePlugin] != null) //make sure its not lazy loaded
    require(injector.instance[InMemoryCachePlugin] != null) //make sure its not lazy loaded
    require(injector.instance[PhraseIndexerPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[UserGraphPlugin] != null)
    require(injector.instance[SearchFriendGraphPlugin] != null)
    require(injector.instance[LoadBalancerCheckPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[LibraryIndexerPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[LibraryMembershipIndexerPlugin] != null) //make sure its not lazy loaded
    require(injector.instance[KeepIndexerPlugin] != null) //make sure its not lazy loaded
  }
}

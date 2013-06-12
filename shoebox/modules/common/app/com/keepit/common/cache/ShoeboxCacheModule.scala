package com.keepit.common.cache

import com.google.inject.{Provides, Singleton}
import com.keepit.model.{UserConnectionIdCache, ClickHistoryUserIdCache, BrowsingHistoryUserIdCache}
import scala.concurrent.duration._
import com.keepit.search.ActiveExperimentsCache

class ShoeboxCacheModule extends CacheModule {

  @Singleton
  @Provides
  def browsingHistoryUserIdCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new BrowsingHistoryUserIdCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def clickHistoryUserIdCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ClickHistoryUserIdCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def activeExperimentsCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ActiveExperimentsCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def userConnectionIdCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserConnectionIdCache((outerRepo, 7 days))
}

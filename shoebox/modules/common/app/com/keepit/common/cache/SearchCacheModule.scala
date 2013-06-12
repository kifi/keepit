package com.keepit.common.cache

import com.google.inject.{Provides, Singleton}
import com.keepit.model.{UserConnectionIdCache, ClickHistoryUserIdCache, BrowsingHistoryUserIdCache}
import scala.concurrent.duration._
import com.keepit.search.ActiveExperimentsCache

class SearchCacheModule extends CacheModule {

  @Singleton
  @Provides
  def browsingHistoryUserIdCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new BrowsingHistoryUserIdCache((innerRepo, 10 seconds), (outerRepo, 7 days))

  @Singleton
  @Provides
  def clickHistoryUserIdCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ClickHistoryUserIdCache((innerRepo, 500 milliseconds), (outerRepo, 7 days))

  @Singleton
  @Provides
  def activeExperimentsCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ActiveExperimentsCache((innerRepo, 5 minutes), (outerRepo, 7 days))

  @Singleton
  @Provides
  def userConnectionIdCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserConnectionIdCache((innerRepo, 10 seconds), (outerRepo, 7 days))
}

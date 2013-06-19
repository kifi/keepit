package com.keepit.common.cache

import com.google.inject.{Provides, Singleton}
import com.keepit.model.{UserConnectionIdCache, ClickHistoryUserIdCache, BrowsingHistoryUserIdCache}
import scala.concurrent.duration._
import com.keepit.search.ActiveExperimentsCache
import com.keepit.common.social.BasicUserUserIdCache

class ShoeboxCacheModule extends CacheModule {

  def configure {
    install(new MemcachedCacheModule)
  }

  @Singleton
  @Provides
  def basicUserUserIdCache(outerRepo: FortyTwoCachePlugin) =
    new BasicUserUserIdCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def browsingHistoryUserIdCache(outerRepo: FortyTwoCachePlugin) =
    new BrowsingHistoryUserIdCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def clickHistoryUserIdCache(outerRepo: FortyTwoCachePlugin) =
    new ClickHistoryUserIdCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def activeExperimentsCache(outerRepo: FortyTwoCachePlugin) =
    new ActiveExperimentsCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def userConnectionIdCache(outerRepo: FortyTwoCachePlugin) =
    new UserConnectionIdCache((outerRepo, 7 days))
}

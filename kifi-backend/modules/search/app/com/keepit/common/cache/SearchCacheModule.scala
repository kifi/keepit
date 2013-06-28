package com.keepit.common.cache

import com.google.inject.{Provides, Singleton}
import com.keepit.model._
import scala.concurrent.duration._
import com.keepit.search.ActiveExperimentsCache
import com.keepit.common.social.BasicUserUserIdCache

case class SearchCacheModule(cachePluginModules: CachePluginModule*) extends CacheModule(cachePluginModules:_*) {

  @Singleton
  @Provides
  def basicUserUserIdCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new BasicUserUserIdCache((innerRepo, 5 minutes), (outerRepo, 7 days))

  @Singleton
  @Provides
  def bookmarkUriUserCache(outerRepo: FortyTwoCachePlugin) =
    new BookmarkUriUserCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def normalizedURICache(outerRepo: FortyTwoCachePlugin) =
    new NormalizedURICache((outerRepo, 7 days))

  @Singleton
  @Provides
  def socialUserInfoUserCache(outerRepo: FortyTwoCachePlugin) =
    new SocialUserInfoUserCache((outerRepo, 30 days))

  @Singleton
  @Provides
  def socialUserInfoNetworkCache(outerRepo: FortyTwoCachePlugin) =
    new SocialUserInfoNetworkCache((outerRepo, 30 days))

  @Singleton
  @Provides
  def userExternalIdCache(outerRepo: FortyTwoCachePlugin) =
    new UserExternalIdCache((outerRepo, 24 hours))

  @Singleton
  @Provides
  def userSessionExternalIdCache(outerRepo: FortyTwoCachePlugin) =
    new UserSessionExternalIdCache((outerRepo, 24 hours))

  @Singleton
  @Provides
  def externalUserIdCache(outerRepo: FortyTwoCachePlugin) =
    new ExternalUserIdCache((outerRepo, 24 hours))

  @Singleton
  @Provides
  def userExperimentCache(outerRepo: FortyTwoCachePlugin) =
    new UserExperimentCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def browsingHistoryUserIdCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new BrowsingHistoryUserIdCache((innerRepo, 10 seconds), (outerRepo, 7 days))

  @Singleton
  @Provides
  def clickHistoryUserIdCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ClickHistoryUserIdCache((innerRepo, 50 milliseconds), (outerRepo, 7 days))

  @Singleton
  @Provides
  def activeExperimentsCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ActiveExperimentsCache((innerRepo, 5 minutes), (outerRepo, 7 days))

  @Singleton
  @Provides
  def userConnectionIdCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserConnectionIdCache((innerRepo, 10 seconds), (outerRepo, 7 days))
}

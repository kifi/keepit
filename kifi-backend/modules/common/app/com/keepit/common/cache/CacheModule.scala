package com.keepit.common.cache

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import com.keepit.model._
import scala.concurrent.duration._
import com.keepit.common.social.{CommentWithBasicUserCache, BasicUserUserIdCache}
import com.keepit.search.ActiveExperimentsCache

abstract class CacheModule extends ScalaModule {
  def configure() {
    install(new MemcachedCacheModule)
    install(new EhCacheCacheModule)
  }

  @Singleton
  @Provides
  def basicUserUserIdCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new BasicUserUserIdCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def commentWithBasicUserCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new CommentWithBasicUserCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def bookmarkUriUserCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new BookmarkUriUserCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def userCollectionCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserCollectionsCache((outerRepo, 1 day))

  @Singleton
  @Provides
  def collectionsForBookmarkCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new CollectionsForBookmarkCache((outerRepo, 1 day))

  @Singleton
  @Provides
  def normalizedURICache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new NormalizedURICache((outerRepo, 7 days))

  @Singleton
  @Provides
  def socialUserInfoUserCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SocialUserInfoUserCache((outerRepo, 30 days))

  @Singleton
  @Provides
  def socialUserInfoNetworkCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SocialUserInfoNetworkCache((outerRepo, 30 days))

  @Singleton
  @Provides
  def unscrapableAllCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UnscrapableAllCache((outerRepo, 0 second))

  @Singleton
  @Provides
  def userExternalIdCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserExternalIdCache((outerRepo, 24 hours))

  @Singleton
  @Provides
  def userIdCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserIdCache((outerRepo, 24 hours))

  @Singleton
  @Provides
  def userSessionExternalIdCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserSessionExternalIdCache((outerRepo, 24 hours))

  @Singleton
  @Provides
  def externalUserIdCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ExternalUserIdCache((outerRepo, 24 hours))

  @Singleton
  @Provides
  def userExperimentCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserExperimentCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def sliderHistoryUserIdCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SliderHistoryUserIdCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def bookmarkCountCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new BookmarkCountCache((outerRepo, 1 hour))

  @Singleton
  @Provides
  def commentCountUriIdCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new CommentCountUriIdCache((outerRepo, 1 hour))

  @Singleton
  @Provides
  def userValueCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserValueCache((outerRepo, 7 days))
}

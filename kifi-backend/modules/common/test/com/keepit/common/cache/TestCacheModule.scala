package com.keepit.common.cache

import com.google.inject.{Provides, Singleton}
import com.keepit.model._
import com.keepit.search.ActiveExperimentsCache
import scala.concurrent.duration._
import com.keepit.social.{CommentWithBasicUserCache, BasicUserUserIdCache}

case class TestCacheModule() extends CacheModule(HashMapMemoryCacheModule()) {
  @Singleton
  @Provides
  def basicUserUserIdCache(outerRepo: FortyTwoCachePlugin) =
    new BasicUserUserIdCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def normalizedURIUrlHashCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new NormalizedURIUrlHashCache((innerRepo, 1 second), (outerRepo, 7 days))

  @Singleton
  @Provides
  def commentWithBasicUserCache(outerRepo: FortyTwoCachePlugin) =
    new CommentWithBasicUserCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def bookmarkUriUserCache(outerRepo: FortyTwoCachePlugin) =
    new BookmarkUriUserCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def userCollectionCache(outerRepo: FortyTwoCachePlugin) =
    new UserCollectionsCache((outerRepo, 1 day))

  @Singleton
  @Provides
  def collectionsForBookmarkCache(outerRepo: FortyTwoCachePlugin) =
    new CollectionsForBookmarkCache((outerRepo, 1 day))

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
  def unscrapableAllCache(outerRepo: FortyTwoCachePlugin) =
    new UnscrapableAllCache((outerRepo, 30 days))

  @Singleton
  @Provides
  def userExternalIdCache(outerRepo: FortyTwoCachePlugin) =
    new UserExternalIdCache((outerRepo, 24 hours))

  @Singleton
  @Provides
  def userIdCache(outerRepo: FortyTwoCachePlugin) =
    new UserIdCache((outerRepo, 24 hours))

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
  def sliderHistoryUserIdCache(outerRepo: FortyTwoCachePlugin) =
    new SliderHistoryUserIdCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def bookmarkCountCache(outerRepo: FortyTwoCachePlugin) =
    new BookmarkCountCache((outerRepo, 1 day))

  @Singleton
  @Provides
  def socialUserInfoCountCache(outerRepo: FortyTwoCachePlugin) =
    new SocialUserInfoCountCache((outerRepo, 1 day))

  @Singleton
  @Provides
  def commentCountUriIdCache(outerRepo: FortyTwoCachePlugin) =
    new CommentCountUriIdCache((outerRepo, 1 hour))

  @Singleton
  @Provides
  def userValueCache(outerRepo: FortyTwoCachePlugin) =
    new UserValueCache((outerRepo, 7 days))

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


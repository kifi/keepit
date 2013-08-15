package com.keepit.common.cache

import scala.concurrent.duration._

import com.google.inject.{Provides, Singleton}
import com.keepit.model._
import com.keepit.reports.UserRetentionCache
import com.keepit.search.ActiveExperimentsCache
import com.keepit.social.{CommentWithBasicUserCache, BasicUserUserIdCache}

case class ShoeboxCacheModule(cachePluginModules: CachePluginModule*) extends CacheModule(cachePluginModules:_*) {

  @Singleton
  @Provides
  def commentCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new CommentCache((innerRepo, 1 hours), (outerRepo, 2 days))

  @Singleton
  @Provides
  def playCacheApi(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new PlayCacheApi((innerRepo, 1 second), (outerRepo, 1 hour))

  @Singleton
  @Provides
  def basicUserUserIdCache(outerRepo: FortyTwoCachePlugin) =
    new BasicUserUserIdCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def normalizedURIUrlHashCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new NormalizedURIUrlHashCache((innerRepo, 1 second), (outerRepo, 7 days))

  @Provides @Singleton
  def prepUrlHashToMappedUrlCache(innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new PrepUrlHashToMappedUrlCache((outerRepo, 7 days))

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
  def userRetentionCache(outerRepo: FortyTwoCachePlugin) =
    new UserRetentionCache((outerRepo, 24 hours))

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

  @Singleton
  @Provides
  def unfriendedConnectionsCache(outerRepo: FortyTwoCachePlugin) =
    new UnfriendedConnectionsCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def userConnectionCountCache(outerRepo: FortyTwoCachePlugin) =
    new UserConnectionCountCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def searchFriendsCache(outerRepo: FortyTwoCachePlugin) =
    new SearchFriendsCache((outerRepo, 7 days))

  @Singleton
  @Provides
  def userTopicCache(outerRepo: FortyTwoCachePlugin) =
    new UserTopicCache((outerRepo, 7 days))

}

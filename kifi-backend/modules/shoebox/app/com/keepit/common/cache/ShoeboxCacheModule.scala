package com.keepit.common.cache

import scala.concurrent.duration._
import com.google.inject.{Provides, Singleton}
import com.keepit.model._
import com.keepit.search.{ArticleSearchResultCache, InitialSearchIdCache, ActiveExperimentsCache}
import com.keepit.social.BasicUserUserIdCache
import com.keepit.classify.DomainCache
import com.keepit.common.logging.AccessLog
import com.keepit.common.usersegment.UserSegmentCache
import com.keepit.eliza.model.UserThreadStatsForUserIdCache
import com.keepit.typeahead.socialusers.SocialUserTypeaheadCache
import com.keepit.typeahead.abook.EContactTypeaheadCache

case class
ShoeboxCacheModule(cachePluginModules: CachePluginModule*) extends CacheModule(cachePluginModules:_*) {

  @Singleton
  @Provides
  def playCacheApi(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new PlayCacheApi(stats, accessLog, (innerRepo, 1 second), (outerRepo, 1 hour))

  @Singleton
  @Provides
  def basicUserUserIdCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new BasicUserUserIdCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def userThreadStatsForUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserThreadStatsForUserIdCache(stats, accessLog, (innerRepo, 1 minute), (outerRepo, Duration.Inf))

  @Singleton
  @Provides
  def normalizedURIUrlHashCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new NormalizedURIUrlHashCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def bookmarkUriUserCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new BookmarkUriUserCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def latestBookmarkUriCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new LatestBookmarkUriCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def userCollectionCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new UserCollectionsCache(stats, accessLog, (outerRepo, 1 day))

  @Singleton
  @Provides
  def collectionsForBookmarkCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new CollectionsForBookmarkCache(stats, accessLog, (outerRepo, 1 day))

  @Singleton
  @Provides
  def bookmarksForCollectionCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new BookmarksForCollectionCache(stats, accessLog, (outerRepo, 1 day))

  @Singleton
  @Provides
  def normalizedURICache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new NormalizedURICache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def socialUserInfoUserCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new SocialUserInfoUserCache(stats, accessLog, (outerRepo, 30 days))

  @Singleton
  @Provides
  def socialUserBasicInfoCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SocialUserBasicInfoCache(stats, accessLog, (innerRepo, 3 hours), (outerRepo, 30 days))

  @Singleton
  @Provides
  def socialUserCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SocialUserCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def socialUserInfoNetworkCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SocialUserInfoNetworkCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def socialUserNetworkCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SocialUserNetworkCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def urlPatternRuleAllCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UrlPatternRuleAllCache(stats, accessLog, (innerRepo, 10 seconds), (outerRepo, 30 days))

  @Singleton
  @Provides
  def httpProxyAllCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new HttpProxyAllCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def userExternalIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserExternalIdCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def userIdCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new UserIdCache(stats, accessLog, (outerRepo, 24 hours))

  @Singleton
  @Provides
  def userSessionExternalIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserSessionExternalIdCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 24 hours))

  @Singleton
  @Provides
  def externalUserIdCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new ExternalUserIdCache(stats, accessLog, (outerRepo, 24 hours))

  @Singleton
  @Provides
  def userExperimentCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserExperimentCache(stats, accessLog, (innerRepo, 1 minutes), (outerRepo, 7 days))

  @Singleton
  @Provides
  def sliderHistoryUserIdCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new SliderHistoryUserIdCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def bookmarkCountCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new BookmarkCountCache(stats, accessLog, (outerRepo, 1 day))

  @Singleton
  @Provides
  def socialUserInfoCountCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new SocialUserInfoCountCache(stats, accessLog, (outerRepo, 1 day))

  @Singleton
  @Provides
  def userValueCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new UserValueCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def activeExperimentsCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new ActiveExperimentsCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def userConnectionIdCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new UserConnectionIdCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def unfriendedConnectionsCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new UnfriendedConnectionsCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def userConnectionCountCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new UserConnectionCountCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def searchFriendsCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new SearchFriendsCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def userTopicCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new UserTopicCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def socialUserConnectionsCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new SocialUserConnectionsCache(stats, accessLog, (outerRepo, 6 hours))

  @Singleton
  @Provides
  def friendRequestCountCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new FriendRequestCountCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def domainCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new DomainCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 7 days))

  @Singleton
  @Provides
  def searchIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new InitialSearchIdCache(stats, accessLog, (outerRepo, 1 hour))

  @Singleton
  @Provides
  def searchArticleCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ArticleSearchResultCache(stats, accessLog, (outerRepo, 1 hour))

  @Singleton
  @Provides
  def userSegmentCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserSegmentCache(stats, accessLog, (innerRepo, 12 hours), (outerRepo, 1 day))

  @Provides
  @Singleton
  def extensionVersionCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new ExtensionVersionInstallationIdCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def probabilisticExperimentGeneratorAllCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ProbabilisticExperimentGeneratorAllCache(stats, accessLog, (outerRepo, Duration.Inf))

  @Singleton
  @Provides
  def socialUserTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SocialUserTypeaheadCache(stats, accessLog, (innerRepo, 10 minutes))

  @Singleton
  @Provides
  def econtactTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new EContactTypeaheadCache(stats, accessLog, (innerRepo, 10 minutes))
}

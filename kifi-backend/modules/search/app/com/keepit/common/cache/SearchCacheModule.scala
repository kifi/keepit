package com.keepit.common.cache

import scala.concurrent.duration._
import com.google.inject.{ Provides, Singleton }
import com.keepit.model._
import com.keepit.search._
import com.keepit.social.BasicUserUserIdCache
import com.keepit.common.logging.AccessLog
import com.keepit.common.usersegment.UserSegmentCache
import com.keepit.search.tracker.ClickHistoryUserIdCache
import com.keepit.search.tracker.ClickHistoryBuilder
import com.keepit.search.tracker.ProbablisticLRUChunkCache
import com.keepit.eliza.model.UserThreadStatsForUserIdCache

case class SearchCacheModule(cachePluginModules: CachePluginModule*) extends CacheModule(cachePluginModules: _*) {

  @Singleton
  @Provides
  def playCacheApi(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new PlayCacheApi(stats, accessLog, (innerRepo, 1 second), (outerRepo, 1 hour))

  @Singleton
  @Provides
  def probablisticLRUChunkCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ProbablisticLRUChunkCache(stats, accessLog, (innerRepo, 5 seconds), (outerRepo, Duration.Inf))

  @Singleton
  @Provides
  def userThreadStatsForUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserThreadStatsForUserIdCache(stats, accessLog, (innerRepo, 1 minute), (outerRepo, Duration.Inf))

  @Singleton
  @Provides
  def urlPatternRuleAllCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UrlPatternRuleAllCache(stats, accessLog, (innerRepo, 10 seconds), (outerRepo, 30 days))

  @Singleton
  @Provides
  def basicUserUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new BasicUserUserIdCache(stats, accessLog, (innerRepo, 5 minutes), (outerRepo, 7 days))

  @Singleton
  @Provides
  def bookmarkUriUserCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new KeepUriUserCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def bookmarkCountCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new KeepCountCache(stats, accessLog, (outerRepo, 1 day))

  @Singleton
  @Provides
  def normalizedURICache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new NormalizedURICache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def uriSummaryCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new URISummaryCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 7 days))

  @Singleton
  @Provides
  def socialUserInfoUserCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new SocialUserInfoUserCache(stats, accessLog, (outerRepo, 30 days))

  @Singleton
  @Provides
  def socialUserInfoNetworkCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SocialUserInfoNetworkCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def userExternalIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserExternalIdCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def userIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserIdCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 24 hours))

  @Singleton
  @Provides
  def userImageUrlCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserImageUrlCache(stats, accessLog, (innerRepo, 1 minute), (outerRepo, 10 minute))

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
    new UserExperimentCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 7 days))

  @Singleton
  @Provides
  def clickHistoryUserIdCache(format: ClickHistoryBuilder, stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ClickHistoryUserIdCache(format, stats, accessLog, (innerRepo, 10 seconds), (outerRepo, Duration.Inf))

  @Singleton
  @Provides
  def activeExperimentsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ActiveExperimentsCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def userConnectionIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserConnectionIdCache(stats, accessLog, (innerRepo, 10 seconds), (outerRepo, 7 days))

  @Singleton
  @Provides
  def userConnectionCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserConnectionCountCache(stats, accessLog, (innerRepo, 10 seconds), (outerRepo, 7 days))

  @Singleton
  @Provides
  def searchFriendsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SearchFriendsCache(stats, accessLog, (innerRepo, 10 seconds), (outerRepo, 7 days))

  @Singleton
  @Provides
  def normalizedURIUrlHashCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new NormalizedURIUrlHashCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def searchIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new InitialSearchIdCache(stats, accessLog, (innerRepo, 5 seconds), (outerRepo, 1 hour))

  @Singleton
  @Provides
  def searchArticleCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ArticleSearchResultCache(stats, accessLog, (innerRepo, 10 seconds), (outerRepo, 1 hour))

  @Provides
  @Singleton
  def userValueCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new UserValueCache(stats, accessLog, (outerRepo, 7 days))

  @Provides @Singleton
  def systemValueCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new SystemValueCache(stats, accessLog, (outerRepo, 7 days))

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

  @Provides @Singleton
  def verifiedEmailUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new VerifiedEmailUserIdCache(stats, accessLog, (outerRepo, 7 days))
}

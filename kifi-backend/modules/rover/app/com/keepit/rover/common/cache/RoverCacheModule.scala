package com.keepit.rover.common.cache

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.logging.AccessLog
import com.keepit.eliza.model.UserThreadStatsForUserIdCache
import com.keepit.graph.model._
import com.keepit.model._
import com.keepit.model.cache.UserSessionViewExternalIdCache
import com.keepit.rover.article.ArticleInfoUriCache
import com.keepit.rover.document.{ RecentFetches, RecentFetchesDomainCache }
import com.keepit.rover.model.RoverHttpProxyAllCache
import com.keepit.rover.sensitivity.UriSensitivityCache
import com.keepit.social.BasicUserUserIdCache
import com.keepit.search.{ ArticleSearchResultCache, InitialSearchIdCache, ActiveExperimentsCache }
import com.keepit.common.usersegment.UserSegmentCache
import scala.concurrent.duration._
import com.keepit.common.cache._

case class RoverCacheModule(cachePluginModules: CachePluginModule*) extends CacheModule(cachePluginModules: _*) {

  @Singleton
  @Provides
  def playCacheApi(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new PlayCacheApi(stats, accessLog, (innerRepo, 1 second), (outerRepo, 1 hour))

  @Singleton
  @Provides
  def userThreadStatsForUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserThreadStatsForUserIdCache(stats, accessLog, (innerRepo, 1 minute), (outerRepo, 30 days))

  @Singleton
  @Provides
  def urlPatternRuleAllCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UrlPatternRulesAllCache(stats, accessLog, (innerRepo, 10 hours), (outerRepo, 30 days))

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
    new URISummaryCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

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
    new UserIdCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 7 days))

  @Singleton
  @Provides
  def userSessionExternalIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserSessionViewExternalIdCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 3 days))

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
    new NormalizedURIUrlHashCache(stats, accessLog, (outerRepo, 30 days))

  @Singleton
  @Provides
  def searchIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new InitialSearchIdCache(stats, accessLog, (innerRepo, 5 seconds), (outerRepo, 1 hour))

  @Singleton
  @Provides
  def searchArticleCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ArticleSearchResultCache(stats, accessLog, (innerRepo, 10 seconds), (outerRepo, 1 hour))

  @Singleton
  @Provides
  def userValueCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new UserValueCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def userSegmentCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserSegmentCache(stats, accessLog, (innerRepo, 12 hours), (outerRepo, 1 day))

  @Singleton
  @Provides
  def extensionVersionCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new ExtensionVersionInstallationIdCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def probabilisticExperimentGeneratorAllCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ProbabilisticExperimentGeneratorAllCache(stats, accessLog, (outerRepo, 30 days))

  @Singleton
  @Provides
  def verifiedEmailUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new VerifiedEmailUserIdCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def uriScoreCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ConnectedUriScoreCache(stats, accessLog, (innerRepo, 30 seconds), (outerRepo, 10 minutes))

  @Singleton
  @Provides
  def userScoreCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ConnectedUserScoreCache(stats, accessLog, (innerRepo, 10 minute, 20 minute), (outerRepo, 6 hours, 12 hours))

  @Provides @Singleton
  def allFakeUsersCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new AllFakeUsersCache(stats, accessLog, (innerRepo, 5 minutes), (outerRepo, 7 days))

  @Provides @Singleton
  def sociallyRelatedEntitiesCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new SociallyRelatedEntitiesCache(stats, accessLog, (outerRepo, 7 day))

  @Provides @Singleton
  def librariesWithWriteAccessCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new LibrariesWithWriteAccessCache(stats, accessLog, (outerRepo, 10 minutes))

  @Provides @Singleton
  def userActivePersonasCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserActivePersonasCache(stats, accessLog, (innerRepo, 5 minutes), (outerRepo, 14 days))

  @Singleton
  @Provides
  def httpProxyAllCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new RoverHttpProxyAllCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

  @Provides @Singleton
  def articleInfoUriCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new ArticleInfoUriCache(stats, accessLog, (outerRepo, 30 days))

  @Provides @Singleton
  def uriSensitivityCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new UriSensitivityCache(stats, accessLog, (outerRepo, 30 days))

  @Provides @Singleton
  def recentFetchesDomainCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new RecentFetchesDomainCache(stats, accessLog, (outerRepo, RecentFetches.recencyWindow))
}

package com.keepit.common.cache

import com.keepit.eliza.model.UserThreadStatsForUserIdCache
import com.keepit.heimdal.SearchHitReportCache
import com.keepit.model.cache.UserSessionViewExternalIdCache
import com.keepit.model.helprank.{ UriReKeepCountCache, UriDiscoveryCountCache }
import com.keepit.model.{ AnonymousEventDescriptorNameCache, UserEventDescriptorNameCache, SystemEventDescriptorNameCache, NonUserEventDescriptorNameCache }

import scala.concurrent.duration._
import com.keepit.common.logging.AccessLog
import com.google.inject.{ Provides, Singleton }
import com.keepit.model._
import com.keepit.social.BasicUserUserIdCache
import com.keepit.search.ActiveExperimentsCache
import com.keepit.common.usersegment.UserSegmentCache

case class HeimdalCacheModule(cachePluginModules: CachePluginModule*) extends CacheModule(cachePluginModules: _*) {

  @Singleton
  @Provides
  def playCacheApi(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new PlayCacheApi(stats, accessLog, (innerRepo, 1 second), (outerRepo, 1 hour))

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
  def urlPatternRuleAllCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UrlPatternRuleAllCache(stats, accessLog, (innerRepo, 10 seconds), (outerRepo, 30 days))

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
  def userSessionExternalIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserSessionViewExternalIdCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 3 days))

  @Singleton
  @Provides
  def externalUserIdCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new ExternalUserIdCache(stats, accessLog, (outerRepo, 24 hours))

  @Singleton
  @Provides
  def userIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserIdCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 7 days))

  @Singleton
  @Provides
  def userExperimentCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserExperimentCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 7 days))

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
  def activeExperimentsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ActiveExperimentsCache(stats, accessLog, (innerRepo, 5 minutes), (outerRepo, 7 days))

  @Singleton
  @Provides
  def normalizedURIUrlHashCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new NormalizedURIUrlHashCache(stats, accessLog, (outerRepo, 30 days))

  @Provides
  @Singleton
  def systemEventDescriptorNameCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SystemEventDescriptorNameCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 7 days))

  @Provides
  @Singleton
  def userEventDescriptorNameCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserEventDescriptorNameCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 7 days))

  @Provides
  @Singleton
  def anonymousEventDescriptorNameCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new AnonymousEventDescriptorNameCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 7 days))

  @Provides
  @Singleton
  def visitorEventDescriptorNameCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new VisitorEventDescriptorNameCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 7 days))

  @Provides
  @Singleton
  def nonUserEventDescriptorNameCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new NonUserEventDescriptorNameCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 7 days))

  @Provides
  @Singleton
  def userValueCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserValueCache(stats, accessLog, (innerRepo, 30 minutes), (outerRepo, 30 days))

  @Provides @Singleton
  def systemValueCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new SystemValueCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def userSegmentCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserSegmentCache(stats, accessLog, (innerRepo, 12 hours), (outerRepo, 1 day))

  @Provides
  @Singleton
  def extensionVersionCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ExtensionVersionInstallationIdCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 7 days))

  @Singleton
  @Provides
  def probabilisticExperimentGeneratorAllCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ProbabilisticExperimentGeneratorAllCache(stats, accessLog, (outerRepo, 30 days))

  @Provides @Singleton
  def verifiedEmailUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new VerifiedEmailUserIdCache(stats, accessLog, (outerRepo, 7 days))

  @Provides @Singleton
  def allFakeUsersCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new AllFakeUsersCache(stats, accessLog, (innerRepo, 5 minutes), (outerRepo, 7 days))

  @Singleton
  @Provides
  def userThreadStatsForUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserThreadStatsForUserIdCache(stats, accessLog, (innerRepo, 1 minute), (outerRepo, 30 days))

  @Singleton
  @Provides
  def kifiHitCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new SearchHitReportCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def uriDiscoveryCountCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new UriDiscoveryCountCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def uriReKeepCountCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new UriReKeepCountCache(stats, accessLog, (outerRepo, 7 days))

  @Provides @Singleton
  def librariesWithWriteAccessCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new LibrariesWithWriteAccessCache(stats, accessLog, (outerRepo, 10 minutes))

  @Provides @Singleton
  def userActivePersonasCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserActivePersonasCache(stats, accessLog, (innerRepo, 24 hours), (outerRepo, 14 days))

}

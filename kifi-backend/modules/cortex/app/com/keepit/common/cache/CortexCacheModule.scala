package com.keepit.common.cache

import com.keepit.cortex.models.lda.UserLDAStatisticsCache
import com.keepit.model.cache.UserSessionViewExternalIdCache
import com.keepit.rover.model.{ RoverArticleImagesCache, RoverArticleSummaryCache }
import com.keepit.shoebox.model.KeepImagesCache
import com.keepit.slack.models.SlackChannelIntegrationsCache

import scala.concurrent.duration._
import com.google.inject.{ Provides, Singleton }
import com.keepit.model._
import com.keepit.social.{ IdentityUserIdCache, BasicUserUserIdCache }
import com.keepit.search.{ ArticleSearchResultCache, InitialSearchIdCache, ActiveExperimentsCache }
import com.keepit.common.logging.AccessLog
import com.keepit.common.usersegment.UserSegmentCache

case class CortexCacheModule(cachePluginModules: CachePluginModule*) extends CacheModule(cachePluginModules: _*) {

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
  def normalizedURICache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new NormalizedURICache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def socialUserInfoUserCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new SocialUserInfoUserCache(stats, accessLog, (outerRepo, 30 days))

  @Singleton
  @Provides
  def socialUserInfoNetworkCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SocialUserInfoNetworkCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

  @Provides @Singleton
  def userIdentityCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new IdentityUserIdCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def userExternalIdCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new UserExternalIdCache(stats, accessLog, (outerRepo, 24 hours))

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

  @Singleton
  @Provides
  def searchIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new InitialSearchIdCache(stats, accessLog, (outerRepo, 1 hour))

  @Singleton
  @Provides
  def searchArticleCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ArticleSearchResultCache(stats, accessLog, (outerRepo, 1 hour))

  @Provides
  @Singleton
  def userValueCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new UserValueCache(stats, accessLog, (outerRepo, 7 days))

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
    new ProbabilisticExperimentGeneratorAllCache(stats, accessLog, (outerRepo, 30 days))

  @Provides @Singleton
  def userLDAStatsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserLDAStatisticsCache(stats, accessLog, (innerRepo, 24 hours), (outerRepo, 30 days))

  @Provides @Singleton
  def allFakeUsersCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new AllFakeUsersCache(stats, accessLog, (innerRepo, 5 minutes), (outerRepo, 7 days))

  @Provides @Singleton
  def librariesWithWriteAccessCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new LibrariesWithWriteAccessCache(stats, accessLog, (outerRepo, 10 minutes))

  @Provides @Singleton
  def roverArticleSummaryCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new RoverArticleSummaryCache(stats, accessLog, (outerRepo, 30 days))

  @Provides @Singleton
  def roverArticleImagesCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new RoverArticleImagesCache(stats, accessLog, (outerRepo, 30 days))

  @Provides @Singleton
  def keepImagesCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new KeepImagesCache(stats, accessLog, (outerRepo, 30 days))

  @Provides @Singleton
  def primaryOrgForUserCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new PrimaryOrgForUserCache(stats, accessLog, (innerRepo, 5 minutes), (outerRepo, 14 days))

  @Provides @Singleton
  def organizationMembersCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new OrganizationMembersCache(stats, accessLog, (outerRepo, 7 days))

  @Provides @Singleton
  def basicOrganizationIdCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new BasicOrganizationIdCache(stats, accessLog, (outerRepo, 7 days))

  @Provides @Singleton
  def slackIntegrationsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SlackChannelIntegrationsCache(stats, accessLog, (outerRepo, 30 days))

  @Provides @Singleton
  def sourceAttributionKeepIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SourceAttributionKeepIdCache(stats, accessLog, (innerRepo, 1 minute), (outerRepo, 30 days))

  @Provides @Singleton
  def slackTeamIdOrgIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SlackTeamIdOrgIdCache(stats, accessLog, (outerRepo, 7 days))
}

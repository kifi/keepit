package com.keepit.common.cache

import com.keepit.model.cache.UserSessionViewExternalIdCache
import com.keepit.rover.model.{ RoverArticleImagesCache, RoverArticleSummaryCache }
import com.keepit.shoebox.model.KeepImagesCache
import com.keepit.slack.models.SlackChannelIntegrationsCache

import scala.concurrent.duration._
import com.keepit.common.logging.AccessLog
import com.google.inject.{ Provides, Singleton }
import com.keepit.model._
import com.keepit.social.{ IdentityUserIdCache, BasicUserUserIdCache }
import com.keepit.eliza.model._
import com.keepit.search.{ ArticleSearchResultCache, InitialSearchIdCache, ActiveExperimentsCache }
import com.keepit.common.usersegment.UserSegmentCache

case class ElizaCacheModule(cachePluginModules: CachePluginModule*) extends CacheModule(cachePluginModules: _*) {

  @Singleton
  @Provides
  def messageThreadExternalIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new MessageThreadKeepIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def messagesForThreadIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new MessagesByKeepIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userThreadStatsForUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserThreadStatsForUserIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def playCacheApi(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new PlayCacheApi(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def basicUserUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new BasicUserUserIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def bookmarkUriUserCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new KeepUriUserCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def bookmarkCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new KeepCountCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def normalizedURICache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new NormalizedURICache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def searchIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new InitialSearchIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def searchArticleCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new ArticleSearchResultCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def socialUserInfoUserCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SocialUserInfoUserCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def socialUserInfoNetworkCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SocialUserInfoNetworkCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def userIdentityCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new IdentityUserIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userExternalIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserExternalIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userSessionExternalIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserSessionViewExternalIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def externalUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new ExternalUserIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userExperimentCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserExperimentCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userConnectionIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserConnectionIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userConnectionCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserConnectionCountCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def searchFriendsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SearchFriendsCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def activeExperimentsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new ActiveExperimentsCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def normalizedURIUrlHashCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new NormalizedURIUrlHashCache(stats, accessLog, (innerRepo, 1 second))

  @Provides
  @Singleton
  def userValueCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserValueCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userSegmentCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserSegmentCache(stats, accessLog, (innerRepo, 1 second))

  @Provides
  @Singleton
  def extensionVersionCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new ExtensionVersionInstallationIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def probabilisticExperimentGeneratorAllCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new ProbabilisticExperimentGeneratorAllCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def allFakeUsersCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new AllFakeUsersCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def librariesWithWriteAccessCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new LibrariesWithWriteAccessCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def roverArticleSummaryCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new RoverArticleSummaryCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def roverArticleImagesCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new RoverArticleImagesCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def keepImagesCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new KeepImagesCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def primaryOrgForUserCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new PrimaryOrgForUserCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def organizationMembersCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new OrganizationMembersCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def basicOrganizationIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new BasicOrganizationIdCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def slackIntegrationsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SlackChannelIntegrationsCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def sourceAttributionKeepIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SourceAttributionKeepIdCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def slackTeamIdOrgIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SlackTeamIdOrgIdCache(stats, accessLog, (innerRepo, 1 second))
}

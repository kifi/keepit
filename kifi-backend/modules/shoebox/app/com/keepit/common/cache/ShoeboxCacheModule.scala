package com.keepit.common.cache

import com.google.inject.{ Provides, Singleton }
import com.keepit.classify.DomainCache
import com.keepit.commanders._
import com.keepit.commanders.gen.BasicLibraryByIdCache
import com.keepit.common.logging.AccessLog
import com.keepit.common.seo.SiteMapCache
import com.keepit.common.usersegment.UserSegmentCache
import com.keepit.controllers.core.StateTokenCache
import com.keepit.eliza.model.UserThreadStatsForUserIdCache
import com.keepit.graph.model._
import com.keepit.model._
import com.keepit.model.cache.UserSessionViewExternalIdCache
import com.keepit.rover.model.{ RoverArticleImagesCache, RoverArticleSummaryCache }
import com.keepit.search.{ ActiveExperimentsCache, ArticleSearchResultCache, InitialSearchIdCache }
import com.keepit.shoebox.model.KeepImagesCache
import com.keepit.slack.SlackAuthStateCache
import com.keepit.slack.models._
import com.keepit.social.{ BasicUserUserIdCache, IdentityUserIdCache }
import com.keepit.typeahead._

import scala.concurrent.duration._

case class ShoeboxCacheModule(cachePluginModules: CachePluginModule*) extends CacheModule(cachePluginModules: _*) {

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
  def slackTeamMembersCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SlackTeamMembersCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def slackTeamMembersCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SlackTeamMembersCountCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def slackTeamBotsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SlackTeamBotsCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def libraryImageCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new LibraryImageCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def organizationAvatarCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new OrganizationAvatarCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def usernameCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UsernameCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def richIpAddressCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new RichIpAddressCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userThreadStatsForUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserThreadStatsForUserIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def normalizedURIUrlHashCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new NormalizedURIUrlHashCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def bookmarkUriUserCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new KeepUriUserCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def countByLibraryCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new CountByLibraryCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userCollectionCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserCollectionsCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userCollectionSummaryCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserCollectionSummariesCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def collectionsForBookmarkCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new CollectionsForKeepCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def bookmarkCountCollectionCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new KeepCountForCollectionCache(stats, accessLog, (innerRepo, 3 days, 30 days))

  @Singleton
  @Provides
  def normalizedURICache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new NormalizedURICache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def socialUserInfoUserCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SocialUserInfoUserCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def socialUserBasicInfoCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SocialUserBasicInfoCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def socialUserInfoNetworkCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SocialUserInfoNetworkCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def userIdentityCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new IdentityUserIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def urlPatternRuleAllCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UrlPatternRulesAllCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userExternalIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserExternalIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userImageUrlCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserImageUrlCache(stats, accessLog, (innerRepo, 1 second))

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
  def userExperimentCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserExperimentCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def sliderHistoryUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SliderHistoryUserIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def bookmarkCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new KeepCountCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def globalBookmarkCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new GlobalKeepCountCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def socialUserInfoCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SocialUserInfoCountCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userValueCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserValueCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def activeExperimentsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new ActiveExperimentsCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userConnectionIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserConnectionIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def basicUserConnectionIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new BasicUserConnectionIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def unfriendedConnectionsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UnfriendedConnectionsCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userConnectionCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserConnectionCountCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userMutualConnectionCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserMutualConnectionCountCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def searchFriendsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SearchFriendsCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def socialUserConnectionsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SocialUserConnectionsCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def friendRequestCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new FriendRequestCountCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def domainCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new DomainCache(stats, accessLog, (innerRepo, 1 second))

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

  @Singleton
  @Provides
  def socialUserTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SocialUserTypeaheadCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def kifiUserTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new KifiUserTypeaheadCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def libraryTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new LibraryFilterTypeaheadCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def libraryResultTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new LibraryResultTypeaheadCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def relevantSuggestedLibrariesCacheCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new RelevantSuggestedLibrariesCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def libraryResultCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new LibraryResultCache(stats, accessLog, (innerRepo, 30 seconds))

  @Singleton
  @Provides
  def libraryIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new LibraryIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def siteMapCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SiteMapCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def libraryMetadataCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new LibraryMetadataCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userMetadataCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserMetadataCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def orgMetadataCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new OrgMetadataCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def keepMetadataCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new KeepMetadataCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def countWithLibraryIdByAccessCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new CountWithLibraryIdByAccessCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def libraryMembershipCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new LibraryMembershipCountCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def followersCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new FollowersCountCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def libraryInviteIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new LibraryInviteIdCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def libraryMembershipCountByLibIdAndAccessCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new LibraryMembershipCountByLibIdAndAccessCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def uriScoreCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new ConnectedUriScoreCache(stats, accessLog, (innerRepo, 1 second))

  @Singleton
  @Provides
  def userScoreCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new ConnectedUserScoreCache(stats, accessLog, (innerRepo, 10 minute, 20 minute), (innerRepo, 40 hours, 60 hours))

  @Provides @Singleton
  def allFakeUsersCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new AllFakeUsersCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def sociallyRelatedEntitiesForUserCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SociallyRelatedEntitiesForUserCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def sociallyRelatedEntitiesForOrgCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SociallyRelatedEntitiesForOrgCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def userHashtagTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserHashtagTypeaheadCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def StateTokenCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new StateTokenCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def librariesWithWriteAccessCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new LibrariesWithWriteAccessCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def LibrarySuggestedSearchCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new LibrarySuggestedSearchCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def RelatedLibrariesCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new RelatedLibrariesCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def userToDomainCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserToDomainCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def topFollowedLibrariesCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new TopFollowedLibrariesCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def twitterHandleCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new TwitterHandleCache(stats, accessLog, (innerRepo, 1 second))

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
  def keepByIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new KeepByIdCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def userIpAddressCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new UserIpAddressCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def organizationCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new OrganizationCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def primaryOrgForUserCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new PrimaryOrgForUserCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def orgTrackingValuesCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new OrgTrackingValuesCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def organizationExperimentCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new OrganizationExperimentCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def organizationDomainOwnershipCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new OrganizationDomainOwnershipAllCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def organizationMembersCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new OrganizationMembersCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def basicOrganizationIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new BasicOrganizationIdCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def orgPermissionsNamespaceCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new OrganizationPermissionsNamespaceCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def orgPermissionsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new OrganizationPermissionsCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def slackIntegrationsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SlackChannelIntegrationsCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def sourceAttributionKeepIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SourceAttributionKeepIdCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def slackTeamIdOrgIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SlackTeamIdOrgIdCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def slackStateCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new SlackAuthStateCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def inferredKeeperPositionCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new InferredKeeperPositionCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def internalSlackTeamInfo(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new InternalSlackTeamInfoCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def basicLibraryByIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new BasicLibraryByIdCache(stats, accessLog, (innerRepo, 1 second))

  @Provides @Singleton
  def liteLibrarySlackInfoCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin) =
    new LiteLibrarySlackInfoCache(stats, accessLog, (innerRepo, 1 second))
}

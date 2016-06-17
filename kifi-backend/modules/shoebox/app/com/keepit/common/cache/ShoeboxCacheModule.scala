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
  def playCacheApi(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new PlayCacheApi(stats, accessLog, (innerRepo, 1 second), (outerRepo, 1 hour))

  @Singleton
  @Provides
  def basicUserUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new BasicUserUserIdCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 7 days))

  @Singleton
  @Provides
  def slackTeamMembersCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SlackTeamMembersCache(stats, accessLog, (innerRepo, 1 days), (outerRepo, 8 days))

  @Singleton
  @Provides
  def slackTeamMembersCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SlackTeamMembersCountCache(stats, accessLog, (innerRepo, 1 days), (outerRepo, 9 day))

  @Singleton
  @Provides
  def slackTeamBotsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SlackTeamBotsCache(stats, accessLog, (innerRepo, 1 days), (outerRepo, 9 day))

  @Singleton
  @Provides
  def libraryImageCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new LibraryImageCache(stats, accessLog, (innerRepo, 30 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def organizationAvatarCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new OrganizationAvatarCache(stats, accessLog, (innerRepo, 30 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def usernameCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UsernameCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def richIpAddressCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new RichIpAddressCache(stats, accessLog, (innerRepo, 60 minutes), (outerRepo, 3 days))

  @Singleton
  @Provides
  def userThreadStatsForUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserThreadStatsForUserIdCache(stats, accessLog, (innerRepo, 1 minute), (outerRepo, 30 days))

  @Singleton
  @Provides
  def normalizedURIUrlHashCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new NormalizedURIUrlHashCache(stats, accessLog, (outerRepo, 30 days))

  @Singleton
  @Provides
  def bookmarkUriUserCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new KeepUriUserCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def countByLibraryCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new CountByLibraryCache(stats, accessLog, (outerRepo, 30 days))

  @Singleton
  @Provides
  def userCollectionCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new UserCollectionsCache(stats, accessLog, (outerRepo, 7 day))

  @Singleton
  @Provides
  def userCollectionSummaryCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserCollectionSummariesCache(stats, accessLog, (innerRepo, 1 second), (outerRepo, 7 days))

  @Singleton
  @Provides
  def collectionsForBookmarkCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new CollectionsForKeepCache(stats, accessLog, (outerRepo, 7 day))

  @Singleton
  @Provides
  def bookmarkCountCollectionCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new KeepCountForCollectionCache(stats, accessLog, (outerRepo, 3 days, 30 days))

  @Singleton
  @Provides
  def normalizedURICache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new NormalizedURICache(stats, accessLog, (outerRepo, 30 days))

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
  def socialUserInfoNetworkCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SocialUserInfoNetworkCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

  @Provides @Singleton
  def userIdentityCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new IdentityUserIdCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def urlPatternRuleAllCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UrlPatternRulesAllCache(stats, accessLog, (innerRepo, 1 hour), (outerRepo, 30 days))

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
  def userImageUrlCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserImageUrlCache(stats, accessLog, (innerRepo, 10 minute), (outerRepo, 30 days))

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
    new UserExperimentCache(stats, accessLog, (innerRepo, 1 minutes), (outerRepo, 7 days))

  @Singleton
  @Provides
  def sliderHistoryUserIdCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new SliderHistoryUserIdCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def bookmarkCountCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new KeepCountCache(stats, accessLog, (outerRepo, 1 day))

  @Singleton
  @Provides
  def globalBookmarkCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new GlobalKeepCountCache(stats, accessLog, (innerRepo, 10 seconds), (outerRepo, 1 minute))

  @Singleton
  @Provides
  def socialUserInfoCountCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new SocialUserInfoCountCache(stats, accessLog, (outerRepo, 1 day))

  @Singleton
  @Provides
  def userValueCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserValueCache(stats, accessLog, (outerRepo, 3 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def activeExperimentsCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new ActiveExperimentsCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def userConnectionIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserConnectionIdCache(stats, accessLog, (innerRepo, 10 seconds), (outerRepo, 7 days))

  @Singleton
  @Provides
  def basicUserConnectionIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new BasicUserConnectionIdCache(stats, accessLog, (innerRepo, 10 seconds), (outerRepo, 7 days))

  @Singleton
  @Provides
  def unfriendedConnectionsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UnfriendedConnectionsCache(stats, accessLog, (innerRepo, 10 seconds), (outerRepo, 7 days))

  @Singleton
  @Provides
  def userConnectionCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserConnectionCountCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 7 days))

  @Singleton
  @Provides
  def userMutualConnectionCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserMutualConnectionCountCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 7 days))

  @Singleton
  @Provides
  def searchFriendsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SearchFriendsCache(stats, accessLog, (innerRepo, 10 seconds), (outerRepo, 7 days))

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
    new ProbabilisticExperimentGeneratorAllCache(stats, accessLog, (outerRepo, 30 days))

  @Singleton
  @Provides
  def socialUserTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SocialUserTypeaheadCache(stats, accessLog, (outerRepo, 1 hour))

  @Singleton
  @Provides
  def kifiUserTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new KifiUserTypeaheadCache(stats, accessLog, (outerRepo, 1 hour))

  @Singleton
  @Provides
  def libraryTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new LibraryFilterTypeaheadCache(stats, accessLog, (outerRepo, 30 minutes))

  @Singleton
  @Provides
  def libraryResultTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new LibraryResultTypeaheadCache(stats, accessLog, (innerRepo, 20 seconds), (outerRepo, 30 minutes))

  @Singleton
  @Provides
  def relevantSuggestedLibrariesCacheCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new RelevantSuggestedLibrariesCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 4 hours))

  @Singleton
  @Provides
  def libraryResultCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new LibraryResultCache(stats, accessLog, (innerRepo, 30 seconds))

  @Singleton
  @Provides
  def libraryIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new LibraryIdCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def siteMapCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SiteMapCache(stats, accessLog, (innerRepo, 1 hour), (outerRepo, 7 days))

  @Singleton
  @Provides
  def libraryMetadataCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new LibraryMetadataCache(stats, accessLog, (innerRepo, 30 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def userMetadataCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserMetadataCache(stats, accessLog, (innerRepo, 30 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def orgMetadataCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new OrgMetadataCache(stats, accessLog, (innerRepo, 30 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def keepMetadataCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new KeepMetadataCache(stats, accessLog, (innerRepo, 30 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def countWithLibraryIdByAccessCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new CountWithLibraryIdByAccessCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def libraryMembershipCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new LibraryMembershipCountCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def followersCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new FollowersCountCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def libraryInviteIdCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new LibraryInviteIdCache(stats, accessLog, (outerRepo, 10 days))

  @Singleton
  @Provides
  def libraryMembershipCountByLibIdAndAccessCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new LibraryMembershipCountByLibIdAndAccessCache(stats, accessLog, (innerRepo, 2 minutes), (outerRepo, 7 days))

  @Singleton
  @Provides
  def uriScoreCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ConnectedUriScoreCache(stats, accessLog, (innerRepo, 30 seconds), (outerRepo, 10 minutes))

  @Singleton
  @Provides
  def userScoreCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new ConnectedUserScoreCache(stats, accessLog, (innerRepo, 10 minute, 20 minute), (outerRepo, 40 hours, 60 hours))

  @Provides @Singleton
  def allFakeUsersCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new AllFakeUsersCache(stats, accessLog, (innerRepo, 5 minutes), (outerRepo, 7 days))

  @Provides @Singleton
  def sociallyRelatedEntitiesForUserCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SociallyRelatedEntitiesForUserCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 7 days))

  @Provides @Singleton
  def sociallyRelatedEntitiesForOrgCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SociallyRelatedEntitiesForOrgCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 6 hours))

  @Provides @Singleton
  def userHashtagTypeaheadCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new UserHashtagTypeaheadCache(stats, accessLog, (outerRepo, 7 days))

  @Provides @Singleton
  def StateTokenCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new StateTokenCache(stats, accessLog, (outerRepo, 2 hours))

  @Provides @Singleton
  def librariesWithWriteAccessCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new LibrariesWithWriteAccessCache(stats, accessLog, (outerRepo, 10 minutes))

  @Provides @Singleton
  def LibrarySuggestedSearchCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new LibrarySuggestedSearchCache(stats, accessLog, (innerRepo, 5 minutes), (outerRepo, 1 days))

  @Provides @Singleton
  def RelatedLibrariesCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new RelatedLibrariesCache(stats, accessLog, (innerRepo, 15 minutes), (outerRepo, 1 hours))

  @Provides @Singleton
  def userToDomainCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserToDomainCache(stats, accessLog, (innerRepo, 30 minutes), (outerRepo, 30 days))

  @Provides @Singleton
  def topFollowedLibrariesCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new TopFollowedLibrariesCache(stats, accessLog, (innerRepo, 5 minutes), (outerRepo, 12 hours))

  @Provides @Singleton
  def twitterHandleCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new TwitterHandleCache(stats, accessLog, (innerRepo, 5 minutes), (outerRepo, 30 days))

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
  def keepByIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new KeepByIdCache(stats, accessLog, (outerRepo, 7 days))

  @Provides @Singleton
  def userIpAddressCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserIpAddressCache(stats, accessLog, (outerRepo, 1 hour))

  @Provides @Singleton
  def organizationCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new OrganizationCache(stats, accessLog, (outerRepo, 14 days))

  @Provides @Singleton
  def primaryOrgForUserCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new PrimaryOrgForUserCache(stats, accessLog, (outerRepo, 14 days))

  @Provides @Singleton
  def orgTrackingValuesCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new OrgTrackingValuesCache(stats, accessLog, (outerRepo, 14 days))

  @Provides @Singleton
  def organizationExperimentCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new OrganizationExperimentCache(stats, accessLog, (innerRepo, 1 minutes), (outerRepo, 7 days))

  @Provides @Singleton
  def organizationDomainOwnershipCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new OrganizationDomainOwnershipAllCache(stats, accessLog, (outerRepo, 14 days))

  @Provides @Singleton
  def organizationMembersCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new OrganizationMembersCache(stats, accessLog, (outerRepo, 7 days))

  @Provides @Singleton
  def basicOrganizationIdCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new BasicOrganizationIdCache(stats, accessLog, (outerRepo, 7 days))

  @Provides @Singleton
  def orgPermissionsNamespaceCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new OrganizationPermissionsNamespaceCache(stats, accessLog, (outerRepo, 7 days))

  @Provides @Singleton
  def orgPermissionsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new OrganizationPermissionsCache(stats, accessLog, (outerRepo, 7 days))

  @Provides @Singleton
  def slackIntegrationsCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SlackChannelIntegrationsCache(stats, accessLog, (outerRepo, 30 days))

  @Provides @Singleton
  def sourceAttributionKeepIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SourceAttributionKeepIdCache(stats, accessLog, (innerRepo, 1 minute), (outerRepo, 30 days))

  @Provides @Singleton
  def slackTeamIdOrgIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SlackTeamIdOrgIdCache(stats, accessLog, (outerRepo, 7 days))

  @Provides @Singleton
  def slackStateCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SlackAuthStateCache(stats, accessLog, (innerRepo, 10 minute), (outerRepo, 1 day))

  @Provides @Singleton
  def inferredKeeperPositionCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new InferredKeeperPositionCache(stats, accessLog, (innerRepo, 1 day), (outerRepo, 30 days))

  @Provides @Singleton
  def internalSlackTeamInfo(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new InternalSlackTeamInfoCache(stats, accessLog, (outerRepo, 7 days))

  @Provides @Singleton
  def basicLibraryByIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new BasicLibraryByIdCache(stats, accessLog, (innerRepo, 10 seconds), (outerRepo, 7 days))

  @Provides @Singleton
  def liteLibrarySlackInfoCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new LiteLibrarySlackInfoCache(stats, accessLog, (innerRepo, 10 seconds), (outerRepo, 7 days))
}

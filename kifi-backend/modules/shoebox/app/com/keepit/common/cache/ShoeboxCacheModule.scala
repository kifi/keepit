package com.keepit.common.cache

import com.keepit.commanders._
import com.keepit.common.seo.SiteMapCache
import com.keepit.controllers.core.StateTokenCache
import com.keepit.model.cache.UserSessionViewExternalIdCache

import scala.concurrent.duration._
import com.google.inject.{ Provides, Singleton }
import com.keepit.model._
import com.keepit.search.{ ArticleSearchResultCache, InitialSearchIdCache, ActiveExperimentsCache }
import com.keepit.social.{ BasicUserUserIdCache }
import com.keepit.classify.DomainCache
import com.keepit.common.logging.AccessLog
import com.keepit.common.usersegment.UserSegmentCache
import com.keepit.eliza.model.UserThreadStatsForUserIdCache
import com.keepit.typeahead.{ UserHashtagTypeaheadCache, KifiUserTypeaheadCache, SocialUserTypeaheadCache }
import com.keepit.graph.model._

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
  def libraryImageCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new LibraryImageCache(stats, accessLog, (innerRepo, 30 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def usernameCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UsernameCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

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
  def latestBookmarkUriCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new LatestKeepUriCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def latestBookmarkUrlCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new LatestKeepUrlCache(stats, accessLog, (outerRepo, 30 days))

  @Singleton
  @Provides
  def userCollectionCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new UserCollectionsCache(stats, accessLog, (outerRepo, 7 day))

  @Singleton
  @Provides
  def basicCollectionByIdCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new BasicCollectionByIdCache(stats, accessLog, (outerRepo, 7 day))

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
  def uriSummaryCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new URISummaryCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 30 days))

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
    new UrlPatternRulesAllCache(stats, accessLog, (innerRepo, 1 hour), (outerRepo, 30 days))

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

  @Provides @Singleton
  def systemValueCache(stats: CacheStatistics, accessLog: AccessLog, outerRepo: FortyTwoCachePlugin) =
    new SystemValueCache(stats, accessLog, (outerRepo, 7 days))

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
  def userConnectionRelationshipCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserConnectionRelationshipCache(stats, accessLog, (innerRepo, 10 seconds), (outerRepo, 30 days))

  @Singleton
  @Provides
  def userFollowerRelationshipCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserFollowerRelationshipCache(stats, accessLog, (innerRepo, 10 seconds), (outerRepo, 30 days))

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

  @Provides @Singleton
  def verifiedEmailUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new VerifiedEmailUserIdCache(stats, accessLog, (outerRepo, 7 days))

  @Singleton
  @Provides
  def pageInfoUri(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new PageInfoUriCache(stats, accessLog, (outerRepo, 10 days))

  @Provides @Singleton
  def uriWordCountCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new NormalizedURIWordCountCache(stats, accessLog, (innerRepo, 5 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def libraryIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new LibraryIdCache(stats, accessLog, (innerRepo, 5 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def siteMapCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SiteMapCache(stats, accessLog, (innerRepo, 1 hour), (outerRepo, 7 days))

  @Singleton
  @Provides
  def libraryMetadataCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new LibraryMetadataCache(stats, accessLog, (innerRepo, 1 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def userMetadataCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserMetadataCache(stats, accessLog, (innerRepo, 1 minutes), (outerRepo, 30 days))

  @Singleton
  @Provides
  def libraryMemberIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new LibraryMembershipIdCache(stats, accessLog, (innerRepo, 1 seconds), (outerRepo, 10 days))

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
  def libraryInfoIdCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new LibraryInfoIdCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 7 days))

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
    new ConnectedUserScoreCache(stats, accessLog, (innerRepo, 10 minute, 20 minute), (outerRepo, 6 hours, 12 hours))

  @Provides @Singleton
  def allFakeUsersCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new AllFakeUsersCache(stats, accessLog, (innerRepo, 5 minutes), (outerRepo, 7 days))

  @Provides @Singleton
  def sociallyRelatedEntitiesCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new SociallyRelatedEntitiesCache(stats, accessLog, (innerRepo, 10 minutes), (outerRepo, 7 days))

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
  def userActivePersonasCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserActivePersonasCache(stats, accessLog, (innerRepo, 5 minutes), (outerRepo, 14 days))

  @Provides @Singleton
  def LibrarySuggestedSearchCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new LibrarySuggestedSearchCache(stats, accessLog, (innerRepo, 5 minutes), (outerRepo, 28 days))

  @Provides @Singleton
  def RelatedLibrariesCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new RelatedLibrariesCache(stats, accessLog, (innerRepo, 30 minutes), (outerRepo, 2 hours))

  @Provides @Singleton
  def userToDomainCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new UserToDomainCache(stats, accessLog, (innerRepo, 30 minutes), (outerRepo, 30 days))

  @Provides @Singleton
  def topFollowedLibrariesCache(stats: CacheStatistics, accessLog: AccessLog, innerRepo: InMemoryCachePlugin, outerRepo: FortyTwoCachePlugin) =
    new TopFollowedLibrariesCache(stats, accessLog, (innerRepo, 5 minutes), (outerRepo, 12 hours))

}

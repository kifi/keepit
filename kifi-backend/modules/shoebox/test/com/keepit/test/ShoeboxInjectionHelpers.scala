package com.keepit.test

import com.keepit.inject._
import com.keepit.common.db.slick.SlickSessionProvider
import com.keepit.model._
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.db.TestSlickSessionProvider
import com.keepit.common.mail.ElectronicMailRepo
import com.google.inject.Injector
import com.keepit.normalizer.NormalizationService

trait ShoeboxInjectionHelpers { self: InjectorProvider =>

  def userSessionRepo(implicit injector: Injector) = inject[UserSessionRepo]
  def userRepo(implicit injector: Injector) = inject[UserRepo]
  def userPictureRepo(implicit injector: Injector) = inject[UserPictureRepo]
  def basicUserRepo(implicit injector: Injector) = inject[BasicUserRepo]
  def userConnRepo(implicit injector: Injector) = inject[UserConnectionRepo]
  def socialConnRepo(implicit injector: Injector) = inject[SocialConnectionRepo]
  def friendRequestRepo(implicit injector: Injector) = inject[FriendRequestRepo]
  def searchFriendRepo(implicit injector: Injector) = inject[SearchFriendRepo]
  def uriRepo(implicit injector: Injector) = inject[NormalizedURIRepo]
  def normalizationService(implicit injector: Injector) = inject[NormalizationService]
  def urlRepo(implicit injector: Injector) = inject[URLRepo]
  def bookmarkRepo(implicit injector: Injector) = inject[BookmarkRepo]
  def socialUserInfoRepo(implicit injector: Injector) = inject[SocialUserInfoRepo]
  def installationRepo(implicit injector: Injector) = inject[KifiInstallationRepo]
  def userExperimentRepo(implicit injector: Injector) = inject[UserExperimentRepo]
  def emailAddressRepo(implicit injector: Injector) = inject[EmailAddressRepo]
  def invitationRepo(implicit injector: Injector) = inject[InvitationRepo]
  def urlPatternRuleRepo(implicit injector: Injector) = inject[UrlPatternRuleRepo]
  def httpProxyRepo(implicit injector: Injector) = inject[HttpProxyRepo]
  def scrapeInfoRepo(implicit injector: Injector) = inject[ScrapeInfoRepo]
  def phraseRepo(implicit injector: Injector) = inject[PhraseRepo]
  def collectionRepo(implicit injector: Injector) = inject[CollectionRepo]
  def keepToCollectionRepo(implicit injector: Injector) = inject[KeepToCollectionRepo]
  def electronicMailRepo(implicit injector: Injector) = inject[ElectronicMailRepo]
  def uriTopicRepoA(implicit injector: Injector) = inject[UriTopicRepoA]
  def uriTopicRepoB(implicit injector: Injector) = inject[UriTopicRepoB]
  def userBookmarkClicksRepo(implicit injector: Injector) = inject[UserBookmarkClicksRepo]
  def failedContentCheckRepo(implicit injector: Injector) = inject[FailedContentCheckRepo]
  def changedURIRepo(implicit injector: Injector) = inject[ChangedURIRepo]
  def sessionProvider(implicit injector: Injector) = inject[SlickSessionProvider].asInstanceOf[TestSlickSessionProvider]
}

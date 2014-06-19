package com.keepit.test

import com.keepit.inject._
import com.keepit.common.db.slick.SlickSessionProvider
import com.keepit.model._
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.db.TestSlickSessionProvider
import com.keepit.common.mail.ElectronicMailRepo
import com.google.inject.Injector
import com.keepit.normalizer.{NormalizedURIInterner, NormalizationService}

trait ShoeboxInjectionHelpers { self: InjectorProvider =>

  def userSessionRepo(implicit injector: Injector) = inject[UserSessionRepo]
  def userRepo(implicit injector: Injector) = inject[UserRepo]
  def rawKeepRepo(implicit injector: Injector) = inject[RawKeepRepo]
  def userPictureRepo(implicit injector: Injector) = inject[UserPictureRepo]
  def basicUserRepo(implicit injector: Injector) = inject[BasicUserRepo]
  def userConnRepo(implicit injector: Injector) = inject[UserConnectionRepo]
  def socialConnRepo(implicit injector: Injector) = inject[SocialConnectionRepo]
  def friendRequestRepo(implicit injector: Injector) = inject[FriendRequestRepo]
  def searchFriendRepo(implicit injector: Injector) = inject[SearchFriendRepo]
  def uriRepo(implicit injector: Injector) = inject[NormalizedURIRepo]
  def normalizedURIInterner(implicit injector: Injector) = inject[NormalizedURIInterner]
  def normalizationService(implicit injector: Injector) = inject[NormalizationService]
  def urlRepo(implicit injector: Injector) = inject[URLRepo]
  def keepRepo(implicit injector: Injector) = inject[KeepRepo]
  def socialUserInfoRepo(implicit injector: Injector) = inject[SocialUserInfoRepo]
  def installationRepo(implicit injector: Injector) = inject[KifiInstallationRepo]
  def userExperimentRepo(implicit injector: Injector) = inject[UserExperimentRepo]
  def emailAddressRepo(implicit injector: Injector) = inject[UserEmailAddressRepo]
  def invitationRepo(implicit injector: Injector) = inject[InvitationRepo]
  def urlPatternRuleRepo(implicit injector: Injector): UrlPatternRuleRepo = inject[UrlPatternRuleRepoImpl]
  def httpProxyRepo(implicit injector: Injector) = inject[HttpProxyRepo]
  def scrapeInfoRepo(implicit injector: Injector) = inject[ScrapeInfoRepo]
  def phraseRepo(implicit injector: Injector) = inject[PhraseRepo]
  def collectionRepo(implicit injector: Injector) = inject[CollectionRepo]
  def keepToCollectionRepo(implicit injector: Injector) = inject[KeepToCollectionRepo]
  def electronicMailRepo(implicit injector: Injector) = inject[ElectronicMailRepo]
  def userBookmarkClicksRepo(implicit injector: Injector) = inject[UserBookmarkClicksRepo]
  def failedContentCheckRepo(implicit injector: Injector) = inject[FailedContentCheckRepo]
  def changedURIRepo(implicit injector: Injector) = inject[ChangedURIRepo]
  def imageInfo(implicit injector: Injector) = inject[ImageInfoRepo]
  def keepClickRepo(implicit injector: Injector) = inject[KeepClickRepo]
  def rekeepRepo(implicit injector: Injector) = inject[ReKeepRepo]
  def sessionProvider(implicit injector: Injector) = inject[SlickSessionProvider].asInstanceOf[TestSlickSessionProvider]
}

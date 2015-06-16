package com.keepit.test

import com.keepit.commanders.{ HandleCommanderImpl, HandleCommander }
import com.keepit.common.db.slick.SlickSessionProvider
import com.keepit.model._
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.db.FakeSlickSessionProvider
import com.keepit.common.mail.ElectronicMailRepo
import com.google.inject.Injector
import com.keepit.normalizer.{ NormalizedURIInterner, NormalizationService }

trait ShoeboxInjectionHelpers { self: TestInjectorProvider =>

  def userSessionRepo(implicit injector: Injector) = inject[UserSessionRepo]
  def userRepo(implicit injector: Injector) = inject[UserRepo]
  def userEmailAddressRepo(implicit injector: Injector) = inject[UserEmailAddressRepo]
  def userCredRepo(implicit injector: Injector) = inject[UserCredRepo]
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
  def phraseRepo(implicit injector: Injector) = inject[PhraseRepo]
  def collectionRepo(implicit injector: Injector) = inject[CollectionRepo]
  def keepToCollectionRepo(implicit injector: Injector) = inject[KeepToCollectionRepo]
  def electronicMailRepo(implicit injector: Injector) = inject[ElectronicMailRepo]
  def failedContentCheckRepo(implicit injector: Injector) = inject[FailedContentCheckRepo]
  def changedURIRepo(implicit injector: Injector) = inject[ChangedURIRepo]
  def sessionProvider(implicit injector: Injector) = inject[SlickSessionProvider].asInstanceOf[FakeSlickSessionProvider]
  def libraryRepo(implicit injector: Injector) = inject[LibraryRepo]
  def libraryMembershipRepo(implicit injector: Injector) = inject[LibraryMembershipRepo]
  def librarySubscriptionRepo(implicit injector: Injector) = inject[LibrarySubscriptionRepo]
  def libraryInviteRepo(implicit injector: Injector) = inject[LibraryInviteRepo]
  def libraryImageRepo(implicit injector: Injector) = inject[LibraryImageRepo]
  def libraryImageRequestRepo(implicit injector: Injector) = inject[LibraryImageRequestRepo]
  def keepImageRepo(implicit injector: Injector) = inject[KeepImageRepo]
  def keepImageRequestRepo(implicit injector: Injector) = inject[KeepImageRequestRepo]
  def handleRepo(implicit injector: Injector) = inject[HandleOwnershipRepo]
  def libraryAliasRepo(implicit injector: Injector) = inject[LibraryAliasRepo]
  def personaRepo(implicit injector: Injector) = inject[PersonaRepo]
  def userPersonaRepo(implicit injector: Injector) = inject[UserPersonaRepo]
  def handleCommander(implicit injector: Injector) = inject[HandleCommander].asInstanceOf[HandleCommanderImpl]
}

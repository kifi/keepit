package com.keepit.test

import com.google.inject.Injector
import com.keepit.commanders._
import com.keepit.common.crypto.KifiUrlRedirectHelper
import com.keepit.common.db.FakeSlickSessionProvider
import com.keepit.common.db.slick.SlickSessionProvider
import com.keepit.common.mail.ElectronicMailRepo
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.FakeClock
import com.keepit.controllers.website.{ DeepLinkRouter, DeepLinkRouterImpl }
import com.keepit.integrity.{ KeepChecker, LibraryChecker, OrganizationChecker }
import com.keepit.model._
import com.keepit.normalizer.{ NormalizationService, NormalizedURIInterner }
import com.keepit.payments._
import com.keepit.slack._
import com.keepit.slack.models.SlackTeamRepo

trait ShoeboxInjectionHelpers { self: TestInjectorProvider =>

  def userSessionRepo(implicit injector: Injector) = inject[UserSessionRepo]
  def userRepo(implicit injector: Injector) = inject[UserRepo]
  def userValueRepo(implicit injector: Injector) = inject[UserValueRepo]
  def userEmailAddressRepo(implicit injector: Injector) = inject[UserEmailAddressRepo]
  def userEmailAddressCommander(implicit injector: Injector) = inject[UserEmailAddressCommander]
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
  def keepRepo(implicit injector: Injector) = inject[KeepRepo]
  def ktlRepo(implicit injector: Injector) = inject[KeepToLibraryRepo]
  def ktuRepo(implicit injector: Injector) = inject[KeepToUserRepo]
  def kteRepo(implicit injector: Injector) = inject[KeepToEmailRepo]
  def keepCommander(implicit injector: Injector) = inject[KeepCommander]
  def keepMutator(implicit injector: Injector) = inject[KeepMutator]
  def ktlCommander(implicit injector: Injector) = inject[KeepToLibraryCommander]
  def ktuCommander(implicit injector: Injector) = inject[KeepToUserCommander]
  def socialUserInfoRepo(implicit injector: Injector) = inject[SocialUserInfoRepo]
  def installationRepo(implicit injector: Injector) = inject[KifiInstallationRepo]
  def userExperimentRepo(implicit injector: Injector) = inject[UserExperimentRepo]
  def orgExperimentRepo(implicit injector: Injector) = inject[OrganizationExperimentRepo]
  def invitationRepo(implicit injector: Injector) = inject[InvitationRepo]
  def phraseRepo(implicit injector: Injector) = inject[PhraseRepo]
  def collectionRepo(implicit injector: Injector) = inject[CollectionRepo]
  def keepToCollectionRepo(implicit injector: Injector) = inject[KeepToCollectionRepo]
  def keepTagRepo(implicit injector: Injector) = inject[KeepTagRepo]
  def electronicMailRepo(implicit injector: Injector) = inject[ElectronicMailRepo]
  def failedContentCheckRepo(implicit injector: Injector) = inject[FailedContentCheckRepo]
  def changedURIRepo(implicit injector: Injector) = inject[ChangedURIRepo]
  def sessionProvider(implicit injector: Injector) = inject[SlickSessionProvider].asInstanceOf[FakeSlickSessionProvider]
  def libraryRepo(implicit injector: Injector) = inject[LibraryRepo]
  def libraryCommander(implicit injector: Injector) = inject[LibraryCommander]
  def libraryMembershipRepo(implicit injector: Injector) = inject[LibraryMembershipRepo]
  def libraryInviteRepo(implicit injector: Injector) = inject[LibraryInviteRepo]
  def libraryImageRepo(implicit injector: Injector) = inject[LibraryImageRepo]
  def libraryImageRequestRepo(implicit injector: Injector) = inject[LibraryImageRequestRepo]
  def keepImageRepo(implicit injector: Injector) = inject[KeepImageRepo]
  def keepImageRequestRepo(implicit injector: Injector) = inject[KeepImageRequestRepo]
  def handleRepo(implicit injector: Injector) = inject[HandleOwnershipRepo]
  def libraryAliasRepo(implicit injector: Injector) = inject[LibraryAliasRepo]
  def handleCommander(implicit injector: Injector) = inject[HandleCommander].asInstanceOf[HandleCommanderImpl]
  def orgRepo(implicit injector: Injector) = inject[OrganizationRepo]
  def orgMembershipRepo(implicit injector: Injector) = inject[OrganizationMembershipRepo]
  def orgInviteRepo(implicit injector: Injector) = inject[OrganizationInviteRepo]
  def orgCommander(implicit injector: Injector) = inject[OrganizationCommander]
  def orgMembershipCommander(implicit injector: Injector) = inject[OrganizationMembershipCommander]
  def orgInviteCommander(implicit injector: Injector) = inject[OrganizationInviteCommander]
  def libraryChecker(implicit injector: Injector) = inject[LibraryChecker]
  def keepChecker(implicit injector: Injector) = inject[KeepChecker]
  def organizationChecker(implicit injector: Injector) = inject[OrganizationChecker]
  def paymentsChecker(implicit injector: Injector) = inject[PaymentsIntegrityChecker]
  def rewardsChecker(implicit injector: Injector) = inject[RewardsChecker]
  def permissionCommander(implicit injector: Injector) = inject[PermissionCommander].asInstanceOf[PermissionCommanderImpl]
  def keepExportCommander(implicit injector: Injector) = inject[KeepExportCommander].asInstanceOf[KeepExportCommanderImpl]
  def orgConfigRepo(implicit injector: Injector) = inject[OrganizationConfigurationRepo]
  def paidPlanRepo(implicit injector: Injector) = inject[PaidPlanRepo]
  def planManagementCommander(implicit injector: Injector) = inject[PlanManagementCommander]
  def deepLinkRouter(implicit injector: Injector) = inject[DeepLinkRouter].asInstanceOf[DeepLinkRouterImpl]
  def paidAccountRepo(implicit injector: Injector) = inject[PaidAccountRepo]
  def accountEventRepo(implicit injector: Injector) = inject[AccountEventRepo]
  def creditCodeInfoRepo(implicit injector: Injector) = inject[CreditCodeInfoRepo]
  def creditRewardRepo(implicit injector: Injector) = inject[CreditRewardRepo]
  def creditRewardCommander(implicit injector: Injector) = inject[CreditRewardCommander]
  def creditRewardInfoCommander(implicit injector: Injector) = inject[CreditRewardInfoCommander].asInstanceOf[CreditRewardInfoCommanderImpl]
  def activityLogCommander(implicit injector: Injector) = inject[ActivityLogCommander]
  def sourceAttributionRepo(implicit injector: Injector) = inject[KeepSourceAttributionRepo]
  def sourceAttributionCommander(implicit injector: Injector) = inject[KeepSourceCommander]
  def tagCommander(implicit injector: Injector) = inject[TagCommander]

  def slackTeamRepo(implicit injector: Injector) = inject[SlackTeamRepo]
  def slackCommander(implicit injector: Injector) = inject[SlackIdentityCommander].asInstanceOf[SlackIdentityCommanderImpl]
  def slackIntegrationCommander(implicit injector: Injector) = inject[SlackIntegrationCommander].asInstanceOf[SlackIntegrationCommanderImpl]
  def slackTeamCommander(implicit injector: Injector) = inject[SlackTeamCommander].asInstanceOf[SlackTeamCommanderImpl]
  def slackChannelCommander(implicit injector: Injector) = inject[SlackChannelCommander]
  def slackInfoCommander(implicit injector: Injector) = inject[SlackInfoCommander].asInstanceOf[SlackInfoCommanderImpl]
  def libToSlackPusher(implicit injector: Injector) = inject[LibraryToSlackChannelPusher].asInstanceOf[LibraryToSlackChannelPusherImpl]
  def slackClient(implicit injector: Injector) = inject[SlackClient].asInstanceOf[FakeSlackClientImpl]
  def fakeClock(implicit injector: Injector) = inject[FakeClock]
}

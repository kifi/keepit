package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.store.{ ImageSize, ImagePath }
import com.keepit.model._
import com.keepit.common.time._

case class UserStatistics(
  user: User,
  connections: Int,
  invitations: Int,
  invitedBy: Seq[User],
  socialUsers: Seq[SocialUserInfo],
  privateKeeps: Int,
  publicKeeps: Int,
  experiments: Set[ExperimentType],
  kifiInstallations: Seq[KifiInstallation],
  librariesCreated: Int,
  librariesFollowed: Int)

case class OrganizationStatistics(
  orgId: Id[Organization],
  ownerId: Id[User],
  handle: OrganizationHandle,
  name: String,
  description: Option[String],
  numLibraries: Int,
  numTotalKeeps: Int,
  numTotalChats: Int,
  members: Set[OrganizationMembership],
  candidates: Set[OrganizationMembershipCandidate],
  userStatistics: Map[Id[User], UserStatistics])

class UserStatisticsCommander @Inject() (
    db: Database,
    kifiInstallationRepo: KifiInstallationRepo,
    keepRepo: KeepRepo,
    emailRepo: UserEmailAddressRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    userConnectionRepo: UserConnectionRepo,
    invitationRepo: InvitationRepo,
    userRepo: UserRepo,
    userExperimentRepo: UserExperimentRepo,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgMembershipCandidateRepo: OrganizationMembershipCandidateRepo) {

  def invitedBy(socialUserIds: Seq[Id[SocialUserInfo]], emails: Seq[UserEmailAddress])(implicit s: RSession): Seq[User] = {
    val invites = invitationRepo.getByRecipientSocialUserIdsAndEmailAddresses(socialUserIds.toSet, emails.map(_.address).toSet)
    val inviters = invites.map(_.senderUserId).flatten
    userRepo.getAllUsers(inviters).values.toSeq
  }

  def userStatistics(user: User, socialUserInfos: Map[Id[User], Seq[SocialUserInfo]])(implicit s: RSession): UserStatistics = {
    val kifiInstallations = kifiInstallationRepo.all(user.id.get).sortWith((a, b) => b.updatedAt.isBefore(a.updatedAt)).take(3)
    val (privateKeeps, publicKeeps) = keepRepo.getPrivatePublicCountByUser(user.id.get)
    val emails = emailRepo.getAllByUser(user.id.get)
    val librariesCountsByAccess = libraryMembershipRepo.countsWithUserIdAndAccesses(user.id.get, Set(LibraryAccess.OWNER, LibraryAccess.READ_ONLY))
    val librariesCreated = librariesCountsByAccess(LibraryAccess.OWNER) - 2 //ignoring main and secret
    val librariesFollowed = librariesCountsByAccess(LibraryAccess.READ_ONLY)

    UserStatistics(user,
      userConnectionRepo.getConnectionCount(user.id.get),
      invitationRepo.countByUser(user.id.get),
      invitedBy(socialUserInfos.getOrElse(user.id.get, Seq()).map(_.id.get), emails),
      socialUserInfos.getOrElse(user.id.get, Seq()),
      privateKeeps,
      publicKeeps,
      userExperimentRepo.getUserExperiments(user.id.get),
      kifiInstallations,
      librariesCreated,
      librariesFollowed)
  }

  def organizationStatistics(orgId: Id[Organization])(implicit session: RSession): OrganizationStatistics = {
    val org = orgRepo.get(orgId)
    val libraries = libraryRepo.getBySpace(LibrarySpace.fromOrganizationId(orgId))
    val numTotalKeeps = libraries.map { lib => keepRepo.getCountByLibrary(lib.id.get) }.sum
    val numTotalChats = 42 // TODO(ryan): find the actual number of chats from Eliza

    val members = orgMembershipRepo.getAllByOrgId(orgId).toSet
    val candidates = orgMembershipCandidateRepo.getAllByOrgId(orgId).toSet
    val userIds = members.map(_.userId) ++ candidates.map(_.userId)
    val userStats = userIds.map { uid => uid -> userStatistics(userRepo.get(uid), Map.empty) }.toMap

    OrganizationStatistics(
      orgId = orgId,
      ownerId = org.ownerId,
      handle = org.getHandle,
      name = org.name,
      description = org.description,
      numLibraries = libraries.size,
      numTotalKeeps = numTotalKeeps,
      numTotalChats = numTotalChats,
      members = members,
      candidates = candidates,
      userStatistics = userStats
    )
  }

}

package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.abook.model.OrganizationInviteRecommendation
import com.keepit.common.core.futureExtensionOps
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._

import scala.concurrent.{ ExecutionContext, Future }

case class UserStatistics(
  user: User,
  connections: Int,
  invitations: Int,
  invitedBy: Seq[User],
  socialUsers: Seq[SocialUserInfo],
  privateKeeps: Int,
  publicKeeps: Int,
  experiments: Set[UserExperimentType],
  kifiInstallations: Seq[KifiInstallation],
  librariesCreated: Int,
  librariesFollowed: Int,
  orgs: Seq[Organization],
  orgCandidates: Seq[Organization])

case class OrganizationStatisticsOverview(
  org: Organization,
  orgId: Id[Organization],
  pubId: PublicId[Organization],
  ownerId: Id[User],
  handle: OrganizationHandle,
  name: String,
  description: Option[String],
  numLibraries: Int,
  numKeeps: Int,
  members: Set[OrganizationMembership],
  candidates: Set[OrganizationMembershipCandidate])

case class MemberStatistics(
  user: User,
  numChats: Int,
  numPublicKeeps: Int,
  numLibrariesCreated: Int,
  numLibrariesCollaborating: Int,
  numLibrariesFollowing: Int,

  numSharedChats: Int,
  numSharedLibraries: Int)

case class OrganizationStatistics(
  org: Organization,
  orgId: Id[Organization],
  pubId: PublicId[Organization],
  ownerId: Id[User],
  handle: OrganizationHandle,
  name: String,
  description: Option[String],
  numLibraries: Int,
  numKeeps: Int,
  numChats: Int,
  members: Set[OrganizationMembership],
  candidates: Set[OrganizationMembershipCandidate],
  membersStatistics: Map[Id[User], MemberStatistics],
  memberRecommendations: Seq[OrganizationMemberRecommendationInfo],
  experiments: Set[OrganizationExperimentType])

case class OrganizationMemberRecommendationInfo(
  userOrEmail: Either[User, EmailAddress],
  associatedOrgs: Set[Organization],
  score: Double)

class UserStatisticsCommander @Inject() (
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext,
    db: Database,
    kifiInstallationRepo: KifiInstallationRepo,
    keepRepo: KeepRepo,
    emailRepo: UserEmailAddressRepo,
    elizaClient: ElizaServiceClient,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    userConnectionRepo: UserConnectionRepo,
    invitationRepo: InvitationRepo,
    userRepo: UserRepo,
    userExperimentRepo: UserExperimentRepo,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgMembershipCandidateRepo: OrganizationMembershipCandidateRepo,
    orgExperimentsRepo: OrganizationExperimentRepo,
    userValueRepo: UserValueRepo,
    abook: ABookServiceClient) {

  def invitedBy(socialUserIds: Seq[Id[SocialUserInfo]], emails: Seq[UserEmailAddress])(implicit s: RSession): Seq[User] = {
    val invites = invitationRepo.getByRecipientSocialUserIdsAndEmailAddresses(socialUserIds.toSet, emails.map(_.address).toSet)
    val inviters = invites.flatMap(_.senderUserId)
    userRepo.getAllUsers(inviters).values.toSeq
  }

  def userStatistics(user: User, socialUserInfos: Map[Id[User], Seq[SocialUserInfo]])(implicit s: RSession): UserStatistics = {
    val kifiInstallations = kifiInstallationRepo.all(user.id.get).sortWith((a, b) => b.updatedAt.isBefore(a.updatedAt)).take(3)
    val (privateKeeps, publicKeeps) = keepRepo.getPrivatePublicCountByUser(user.id.get)
    val emails = emailRepo.getAllByUser(user.id.get)
    val librariesCountsByAccess = libraryMembershipRepo.countsWithUserIdAndAccesses(user.id.get, Set(LibraryAccess.OWNER, LibraryAccess.READ_ONLY))
    val librariesCreated = librariesCountsByAccess(LibraryAccess.OWNER) - 2 //ignoring main and secret
    val librariesFollowed = librariesCountsByAccess(LibraryAccess.READ_ONLY)
    val orgs = orgRepo.getByIds(orgMembershipRepo.getAllByUserId(user.id.get).map(_.organizationId).toSet).values.toList
    val orgCandidates = orgRepo.getByIds(orgMembershipCandidateRepo.getAllByUserId(user.id.get).map(_.orgId).toSet).values.toList

    UserStatistics(
      user,
      userConnectionRepo.getConnectionCount(user.id.get),
      invitationRepo.countByUser(user.id.get),
      invitedBy(socialUserInfos.getOrElse(user.id.get, Seq()).map(_.id.get), emails),
      socialUserInfos.getOrElse(user.id.get, Seq()),
      privateKeeps,
      publicKeeps,
      userExperimentRepo.getUserExperiments(user.id.get),
      kifiInstallations,
      librariesCreated,
      librariesFollowed,
      orgs,
      orgCandidates
    )
  }

  def membersStatistics(userIds: Set[Id[User]])(implicit session: RSession): Future[Map[Id[User], MemberStatistics]] = {
    val membersStatsFut = userIds.map { userId =>
      val numChatsFut = elizaClient.getUserThreadStats(userId)
      val (numPrivateKeeps, numPublicKeeps) = keepRepo.getPrivatePublicCountByUser(userId)
      val librariesCountsByAccess = libraryMembershipRepo.countsWithUserIdAndAccesses(userId, Set(LibraryAccess.OWNER, LibraryAccess.READ_ONLY, LibraryAccess.READ_WRITE))
      val numLibrariesCreated = librariesCountsByAccess(LibraryAccess.OWNER) // I prefer to see the Main and Secret libraries included
      val numLibrariesFollowing = librariesCountsByAccess(LibraryAccess.READ_ONLY)
      val numLibrariesCollaborating = librariesCountsByAccess(LibraryAccess.READ_WRITE)
      val user = userRepo.get(userId)
      for (
        numChats <- numChatsFut
      ) yield {
        userId -> MemberStatistics(
          user = user,
          numChats = numChats.all,
          numPublicKeeps = numPublicKeeps,
          numLibrariesCreated = numLibrariesCreated,
          numLibrariesCollaborating = numLibrariesCollaborating,
          numLibrariesFollowing = numLibrariesFollowing,
          numSharedLibraries = 0, //TODO(ryan): fix
          numSharedChats = 0 // TODO(ryan): fix
        )
      }
    }
    Future.sequence(membersStatsFut).imap(_.toMap)
  }

  def organizationStatistics(orgId: Id[Organization], adminId: Id[User], numMemberRecos: Int = 50): Future[OrganizationStatistics] = {
    val (org, libraries, numKeeps, members, candidates, experiments, membersStatsFut) = db.readOnlyMaster { implicit session =>
      val org = orgRepo.get(orgId)
      val libraries = libraryRepo.getBySpace(LibrarySpace.fromOrganizationId(orgId))
      val numKeeps = libraries.map(_.keepCount).sum
      val members = orgMembershipRepo.getAllByOrgId(orgId)
      val candidates = orgMembershipCandidateRepo.getAllByOrgId(orgId).toSet
      val userIds = members.map(_.userId) ++ candidates.map(_.userId)
      val experiments = orgExperimentsRepo.getOrganizationExperiments(orgId)
      val membersStatsFut = membersStatistics(userIds)
      (org, libraries, numKeeps, members, candidates, experiments, membersStatsFut)
    }

    val fMemberRecommendations = abook.getRecommendationsForOrg(orgId, adminId, disclosePrivateEmails = true, 0, numMemberRecos)

    val fMemberRecoInfos = fMemberRecommendations.map(_.map { memberInviteReco =>
      memberInviteReco.identifier match {
        case Right(email: EmailAddress) => OrganizationMemberRecommendationInfo(Right(email), Set.empty, memberInviteReco.score * 10000)
        case Left(userId: Id[User]) =>
          val (user, orgs) = db.readOnlyMaster { implicit session =>
            val user = userRepo.get(userId)
            val orgs = orgRepo.getByIds((orgMembershipRepo.getAllByUserId(userId).map(_.organizationId) ++ orgMembershipCandidateRepo.getAllByUserId(userId).map(_.orgId)).toSet)
            (user, orgs)
          }
          OrganizationMemberRecommendationInfo(Left(user), orgs.values.toSet, memberInviteReco.score * 10000)
      }
    }.filter { orgReco =>
      orgReco.userOrEmail match {
        case Right(email) => false
        case Left(user) => !candidates.map(_.userId).contains(user.id.get) &&
          !db.readOnlyMaster { implicit session =>
            userExperimentRepo.hasExperiment(user.id.get, UserExperimentType.ADMIN) &&
              userValueRepo.getUserValue(user.id.get, UserValueName.IGNORE_FOR_POTENTIAL_ORGANIZATIONS).isDefined
          }
      }
    })

    val numChats = 42 // TODO(ryan): find the actual number of chats from Eliza

    for {
      membersStats <- membersStatsFut
      memberRecos <- fMemberRecoInfos
    } yield OrganizationStatistics(
      org = org,
      orgId = orgId,
      pubId = Organization.publicId(orgId),
      ownerId = org.ownerId,
      handle = org.getHandle,
      name = org.name,
      description = org.description,
      numLibraries = libraries.size,
      numKeeps = numKeeps,
      numChats = numChats,
      members = members,
      candidates = candidates,
      membersStatistics = membersStats,
      memberRecommendations = memberRecos,
      experiments = experiments
    )
  }
  def organizationStatisticsOverview(orgId: Id[Organization])(implicit session: RSession): OrganizationStatisticsOverview = {
    val org = orgRepo.get(orgId)
    val libraries = libraryRepo.getBySpace(LibrarySpace.fromOrganizationId(orgId))
    val numKeeps = libraries.map(_.keepCount).sum

    val members = orgMembershipRepo.getAllByOrgId(orgId)
    val candidates = orgMembershipCandidateRepo.getAllByOrgId(orgId).toSet
    val userIds = members.map(_.userId) ++ candidates.map(_.userId)

    OrganizationStatisticsOverview(
      org = org,
      orgId = orgId,
      pubId = Organization.publicId(orgId),
      ownerId = org.ownerId,
      handle = org.getHandle,
      name = org.name,
      description = org.description,
      numLibraries = libraries.size,
      numKeeps = numKeeps,
      members = members,
      candidates = candidates
    )
  }

}

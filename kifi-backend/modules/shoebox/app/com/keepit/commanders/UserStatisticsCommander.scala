package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.abook.model.OrganizationInviteRecommendation
import com.keepit.classify.{ DomainRepo, Domain }
import com.keepit.common.core.futureExtensionOps
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.EmailAddress
import com.keepit.eliza.ElizaServiceClient
import com.keepit.eliza.model.GroupThreadStats
import com.keepit.model._
import org.joda.time.DateTime

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
  candidates: Set[OrganizationMembershipCandidate],
  domains: Set[Domain],
  internalMemberChatStats: Int,
  allMemberChatStats: Int)

case class MemberStatistics(
  user: User,
  numChats: Int,
  numPublicKeeps: Int,
  numLibrariesCreated: Int,
  numLibrariesCollaborating: Int,
  numLibrariesFollowing: Int,

  dateLastManualKeep: Option[DateTime])

case class OrganizationStatistics(
  org: Organization,
  orgId: Id[Organization],
  pubId: PublicId[Organization],
  owner: User,
  handle: OrganizationHandle,
  name: String,
  description: Option[String],
  numLibraries: Int,
  numKeeps: Int,
  members: Set[OrganizationMembership],
  candidates: Set[OrganizationMembershipCandidate],
  membersStatistics: Map[Id[User], MemberStatistics],
  memberRecommendations: Seq[OrganizationMemberRecommendationInfo],
  experiments: Set[OrganizationExperimentType],
  domains: Set[Domain],
  internalMemberChatStats: Seq[SummaryByYearWeek],
  allMemberChatStats: Seq[SummaryByYearWeek])

case class OrganizationMemberRecommendationInfo(
  user: User,
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
    orgDomainOwnCommander: OrganizationDomainOwnershipCommander,
    userValueRepo: UserValueRepo,
    orgChatStatsCommander: OrganizationChatStatisticsCommander,
    domainRepo: DomainRepo,
    abook: ABookServiceClient,
    airbrake: AirbrakeNotifier) {

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
    val orgCandidates = orgRepo.getByIds(orgMembershipCandidateRepo.getAllByUserId(user.id.get).map(_.organizationId).toSet).values.toList

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
      val dateLastManualKeep = keepRepo.getDateLastManualKeep(userId)
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
          dateLastManualKeep = dateLastManualKeep
        )
      }
    }
    Future.sequence(membersStatsFut).imap(_.toMap)
  }

  def organizationStatistics(orgId: Id[Organization], adminId: Id[User], numMemberRecos: Int): Future[OrganizationStatistics] = {
    val (org, libraries, numKeeps, members, candidates, experiments, membersStatsFut, domains) = db.readOnlyMaster { implicit session =>
      val org = orgRepo.get(orgId)
      val libraries = libraryRepo.getBySpace(LibrarySpace.fromOrganizationId(orgId))
      val numKeeps = libraries.map(_.keepCount).sum
      val members = orgMembershipRepo.getAllByOrgId(orgId)
      val candidates = orgMembershipCandidateRepo.getAllByOrgId(orgId).toSet
      val userIds = members.map(_.userId) ++ candidates.map(_.userId)
      val experiments = orgExperimentsRepo.getOrganizationExperiments(orgId)
      val membersStatsFut = membersStatistics(userIds)
      val domains = orgDomainOwnCommander.getDomainsOwned(orgId)
      (org, libraries, numKeeps, members, candidates, experiments, membersStatsFut, domains)
    }

    val fMemberRecommendations = try {
      abook.getRecommendationsForOrg(orgId, adminId, disclosePrivateEmails = true, 0, numMemberRecos + members.size + candidates.size)
    } catch {
      case ex: Exception => airbrake.notify(ex); Future.successful(Seq.empty[OrganizationInviteRecommendation])
    }

    val fMemberRecoInfos = fMemberRecommendations.map(_.filter { reco =>
      reco.identifier.isLeft &&
        db.readOnlyMaster { implicit session =>
          orgMembershipRepo.getAllByUserId(reco.identifier.left.get).isEmpty &&
            orgMembershipCandidateRepo.getAllByUserId(reco.identifier.left.get).isEmpty &&
            !userExperimentRepo.hasExperiment(reco.identifier.left.get, UserExperimentType.ADMIN) &&
            !userValueRepo.getValue(reco.identifier.left.get, UserValues.ignoreForPotentialOrganizations)
        }
    }.map {
      case OrganizationInviteRecommendation(Left(userId), _, score) =>
        val user = db.readOnlyMaster { implicit session => userRepo.get(userId) }
        OrganizationMemberRecommendationInfo(user, score * 10000)
    })

    val allUsers = members.map(_.userId) | candidates.map(_.userId)

    def summaryByWeek(stat: orgChatStatsCommander.EngagementStat): Future[Seq[SummaryByYearWeek]] =
      stat.summaryBy {
        case GroupThreadStats(_, date, _) => (date.getWeekyear, date.getWeekOfWeekyear)
      }(allUsers).map { stats =>
        stats.map {
          case ((year, week), numUsers) => SummaryByYearWeek(year, week, numUsers)
        }.toSeq.sorted
      }

    val (internalMemberChatStatsF, allMemberChatStatsF) = (summaryByWeek(orgChatStatsCommander.internalChats), summaryByWeek(orgChatStatsCommander.allChats))

    val statsF = for {
      internalMemberChatStats <- internalMemberChatStatsF
      allMemberChatStats <- allMemberChatStatsF
    } yield {
      if (allMemberChatStats.isEmpty) {
        (internalMemberChatStats, allMemberChatStats)
      } else {
        val allStatWeeks = allMemberChatStats ++ internalMemberChatStats
        val min = allStatWeeks.min
        val max = allStatWeeks.max
        val fillInMissing = SummaryByYearWeek.fillInMissing(min, max) _
        (fillInMissing(internalMemberChatStats), fillInMissing(allMemberChatStats))
      }
    }

    for {
      membersStats <- membersStatsFut
      memberRecos <- fMemberRecoInfos
      (internalMemberChatStats, allMemberChatStats) <- statsF
    } yield OrganizationStatistics(
      org = org,
      orgId = orgId,
      pubId = Organization.publicId(orgId),
      owner = membersStats(org.ownerId).user,
      handle = org.handle,
      name = org.name,
      description = org.description,
      numLibraries = libraries.size,
      numKeeps = numKeeps,
      members = members,
      candidates = candidates,
      membersStatistics = membersStats,
      memberRecommendations = memberRecos,
      experiments = experiments,
      domains = domains,
      internalMemberChatStats = internalMemberChatStats,
      allMemberChatStats = allMemberChatStats
    )
  }
  def organizationStatisticsOverview(org: Organization)(implicit session: RSession): Future[OrganizationStatisticsOverview] = {
    val orgId = org.id.get
    val libraries = libraryRepo.getBySpace(LibrarySpace.fromOrganizationId(orgId))
    val numKeeps = libraries.map(_.keepCount).sum

    val members = orgMembershipRepo.getAllByOrgId(orgId)
    val candidates = orgMembershipCandidateRepo.getAllByOrgId(orgId).toSet
    val userIds = members.map(_.userId) ++ candidates.map(_.userId)
    val domains = orgDomainOwnCommander.getDomainsOwned(orgId)

    val allUsers = members.map(_.userId) | candidates.map(_.userId)

    val (internalMemberChatStatsF, allMemberChatStatsF) = (orgChatStatsCommander.internalChats.summary(allUsers), orgChatStatsCommander.allChats.summary(allUsers))

    for {
      internalMemberChatStats <- internalMemberChatStatsF
      allMemberChatStats <- allMemberChatStatsF
    } yield OrganizationStatisticsOverview(
      org = org,
      orgId = orgId,
      pubId = Organization.publicId(orgId),
      ownerId = org.ownerId,
      handle = org.handle,
      name = org.name,
      description = org.description,
      numLibraries = libraries.size,
      numKeeps = numKeeps,
      members = members,
      candidates = candidates,
      domains = domains,
      internalMemberChatStats = internalMemberChatStats,
      allMemberChatStats = allMemberChatStats
    )
  }

}

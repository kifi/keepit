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
import com.keepit.payments.{ DollarAmount, PlanManagementCommander, PaidPlan }
import com.keepit.model._
import com.keepit.common.time._
import org.joda.time.DateTime

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

case class UserStatistics(
  user: User,
  emailAddress: Option[EmailAddress],
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
  dateLastManualKeep: Option[DateTime],
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
  libStats: LibCountStatistics,
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
  numPrivateKeeps: Int,
  numLibrariesCreated: Int,
  numLibrariesCollaborating: Int,
  numLibrariesFollowing: Int,

  dateLastManualKeep: Option[DateTime])

case class LibCountStatistics(privateLibCount: Int, protectedLibCount: Int, publicLibCount: Int)

object LibCountStatistics {
  def apply(allLibs: Iterable[Library]): LibCountStatistics = {
    val libs = allLibs.filter(_.kind == LibraryKind.USER_CREATED)
    LibCountStatistics(
      libs.count(_.isSecret),
      libs.count(_.visibility == LibraryVisibility.ORGANIZATION),
      libs.count(_.isPublished))
  }
}

case class OrganizationStatistics(
  org: Organization,
  orgId: Id[Organization],
  pubId: PublicId[Organization],
  owner: User,
  handle: OrganizationHandle,
  name: String,
  description: Option[String],
  libStats: LibCountStatistics,
  numKeeps: Int,
  numKeepsLastWeek: Int,
  members: Set[OrganizationMembership],
  candidates: Set[OrganizationMembershipCandidate],
  membersStatistics: Map[Id[User], MemberStatistics],
  memberRecommendations: Seq[OrganizationMemberRecommendationInfo],
  experiments: Set[OrganizationExperimentType],
  domains: Set[Domain],
  internalMemberChatStats: Seq[SummaryByYearWeek],
  allMemberChatStats: Seq[SummaryByYearWeek],
  credit: DollarAmount,
  stripeToken: String,
  billingCycleStart: DateTime,
  plan: PaidPlan)

case class OrganizationMemberRecommendationInfo(
  user: User,
  emailAddress: Option[EmailAddress],
  score: Double)

class UserStatisticsCommander @Inject() (
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext,
    db: Database,
    clock: Clock,
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
    airbrake: AirbrakeNotifier,
    planManagementCommander: PlanManagementCommander) {

  def invitedBy(socialUserIds: Seq[Id[SocialUserInfo]], emails: Seq[UserEmailAddress])(implicit s: RSession): Seq[User] = {
    val invites = invitationRepo.getByRecipientSocialUserIdsAndEmailAddresses(socialUserIds.toSet, emails.map(_.address).toSet)
    val inviters = invites.flatMap(_.senderUserId)
    userRepo.getAllUsers(inviters).values.toSeq
  }

  def userStatistics(user: User, socialUserInfos: Map[Id[User], Seq[SocialUserInfo]])(implicit s: RSession): UserStatistics = {
    val kifiInstallations = kifiInstallationRepo.all(user.id.get).sortWith((a, b) => b.updatedAt.isBefore(a.updatedAt)).take(3)
    val (privateKeeps, publicKeeps) = keepRepo.getPrivatePublicCountByUser(user.id.get)
    val emails = emailRepo.getAllByUser(user.id.get)
    val emailAddress = Try(emailRepo.getByUser(user.id.get)).toOption
    val librariesCountsByAccess = libraryMembershipRepo.countsWithUserIdAndAccesses(user.id.get, Set(LibraryAccess.OWNER, LibraryAccess.READ_ONLY))
    val librariesCreated = librariesCountsByAccess(LibraryAccess.OWNER) - 2 //ignoring main and secret
    val librariesFollowed = librariesCountsByAccess(LibraryAccess.READ_ONLY)
    val latestManualKeepTime = keepRepo.latestManualKeepTime(user.id.get)
    val orgs = orgRepo.getByIds(orgMembershipRepo.getAllByUserId(user.id.get).map(_.organizationId).toSet).values.toList
    val orgCandidates = orgRepo.getByIds(orgMembershipCandidateRepo.getAllByUserId(user.id.get).map(_.organizationId).toSet).values.toList

    UserStatistics(
      user,
      emailAddress,
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
      latestManualKeepTime,
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
      val dateLastManualKeep = keepRepo.latestManualKeepTime(userId)
      val user = userRepo.get(userId)
      for (
        numChats <- numChatsFut
      ) yield {
        userId -> MemberStatistics(
          user = user,
          numChats = numChats.all,
          numPublicKeeps = numPublicKeeps,
          numPrivateKeeps = numPrivateKeeps,
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
    val (members, candidates) = db.readOnlyMaster { implicit session =>
      val members = orgMembershipRepo.getAllByOrgId(orgId)
      val candidates = orgMembershipCandidateRepo.getAllByOrgId(orgId).toSet
      (members, candidates)
    }

    val fMemberRecommendations = try {
      abook.getRecommendationsForOrg(orgId, viewerIdOpt = None, 0, numMemberRecos + candidates.size)
    } catch {
      case ex: Exception => airbrake.notify(ex); Future.successful(Seq.empty[OrganizationInviteRecommendation])
    }

    val (org, libraries, numKeeps, numKeepsLastWeek, experiments, membersStatsFut, domains) = db.readOnlyMaster { implicit session =>
      val org = orgRepo.get(orgId)
      val libraries = libraryRepo.getBySpace(LibrarySpace.fromOrganizationId(orgId))
      val numKeeps = libraries.map(_.keepCount).sum
      val numKeepsLastWeek = keepRepo.getCountByLibrariesSince(libraries.map(_.id.get).toSet, clock.now().minusWeeks(1))
      val userIds = members.map(_.userId) ++ candidates.map(_.userId)
      val experiments = orgExperimentsRepo.getOrganizationExperiments(orgId)
      val membersStatsFut = membersStatistics(userIds)
      val domains = orgDomainOwnCommander.getDomainsOwned(orgId)
      (org, libraries, numKeeps, numKeepsLastWeek, experiments, membersStatsFut, domains)
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
        val (user, emailAddress) = db.readOnlyMaster { implicit session =>
          (userRepo.get(userId), Try(emailRepo.getByUser(userId)).toOption)
        }
        OrganizationMemberRecommendationInfo(user, emailAddress, score * 10000)
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

    val credit = planManagementCommander.getCurrentCredit(orgId)
    val stripeToken = planManagementCommander.getDefaultPaymentMethod(orgId).map(_.stripeToken.token).getOrElse("N/A")
    val plan = planManagementCommander.currentPlan(orgId)
    val billingCycleStart = planManagementCommander.getBillingCycleStart(orgId)

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
      libStats = LibCountStatistics(libraries),
      numKeeps = numKeeps,
      numKeepsLastWeek = numKeepsLastWeek,
      members = members,
      candidates = candidates,
      membersStatistics = membersStats,
      memberRecommendations = memberRecos,
      experiments = experiments,
      domains = domains,
      internalMemberChatStats = internalMemberChatStats,
      allMemberChatStats = allMemberChatStats,
      credit = credit,
      stripeToken = stripeToken,
      billingCycleStart = billingCycleStart,
      plan = plan
    )
  }

  def organizationStatisticsOverview(org: Organization): Future[OrganizationStatisticsOverview] = {
    val orgId = org.id.get
    val (allUsers, libraries, members, candidates, domains) = db.readOnlyReplica { implicit session =>
      val members = orgMembershipRepo.getAllByOrgId(orgId)
      val candidates = orgMembershipCandidateRepo.getAllByOrgId(orgId).toSet
      val allUsers = members.map(_.userId) | candidates.map(_.userId)
      val domains = orgDomainOwnCommander.getDomainsOwned(orgId)
      val libraries = libraryRepo.getBySpace(LibrarySpace.fromOrganizationId(orgId)).filterNot(_.kind == LibraryKind.SYSTEM_GUIDE)
      (allUsers, libraries, members, candidates, domains)
    }
    val numKeeps = libraries.map(_.keepCount).sum

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
      libStats = LibCountStatistics(libraries),
      numKeeps = numKeeps,
      members = members,
      candidates = candidates,
      domains = domains,
      internalMemberChatStats = internalMemberChatStats,
      allMemberChatStats = allMemberChatStats
    )
  }

}

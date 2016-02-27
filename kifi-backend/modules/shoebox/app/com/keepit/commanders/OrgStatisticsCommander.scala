package com.keepit.commanders

import com.keepit.classify.NormalizedHostname
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.common.core.futureExtensionOps
import com.google.inject.Inject
import play.api.libs.json.Json
import com.keepit.abook.ABookServiceClient
import com.keepit.abook.model.OrganizationInviteRecommendation
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.common.util.DollarAmount
import com.keepit.eliza.ElizaServiceClient
import com.keepit.eliza.model.GroupThreadStats
import com.keepit.model._
import com.keepit.payments.{ PaidPlan, PaymentStatus, PlanManagementCommander }
import com.keepit.slack.models._
import org.joda.time.DateTime
import com.keepit.common.core._

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future, ExecutionContext }
import scala.util.Try

case class MemberStatistics(
  user: User,
  online: Option[Boolean],
  numChats: Int,
  keepVisibilityCount: KeepVisibilityCount,
  numLibrariesCreated: Int,
  numLibrariesCollaborating: Int,
  numLibrariesFollowing: Int,
  dateLastManualKeep: Option[DateTime],
  lastLocation: Option[RichIpAddress])

case class OrganizationStatistics(
  org: Organization,
  orgId: Id[Organization],
  pubId: PublicId[Organization],
  owner: User,
  handle: OrganizationHandle,
  name: String,
  description: Option[String],
  libStats: LibCountStatistics,
  slackStats: SlackStatistics,
  numKeeps: Int,
  numKeepsLastWeek: Int,
  members: Set[OrganizationMembership],
  candidates: Set[OrganizationMembershipCandidate],
  membersStatistics: Map[Id[User], MemberStatistics],
  memberRecommendations: Seq[OrganizationMemberRecommendationInfo],
  experiments: Set[OrganizationExperimentType],
  domains: Set[NormalizedHostname],
  internalMemberChatStats: Seq[SummaryByYearWeek],
  allMemberChatStats: Seq[SummaryByYearWeek],
  credit: DollarAmount,
  stripeToken: String,
  paymentStatus: PaymentStatus,
  planRenewal: DateTime,
  plan: PaidPlan,
  accountFrozen: Boolean)

case class OrganizationMemberRecommendationInfo(
  user: User,
  emailAddress: Option[EmailAddress],
  score: Double)

case class OrganizationStatisticsMin(
  org: Organization,
  memberCount: Int,
  libCount: Int,
  slackLibs: Int,
  slackTeamSize: Int)

case class OrganizationStatisticsOverview(
  org: Organization,
  orgId: Id[Organization],
  pubId: PublicId[Organization],
  ownerId: Id[User],
  handle: OrganizationHandle,
  name: String,
  description: Option[String],
  libStats: LibCountStatistics,
  slackStats: SlackStatistics,
  numKeeps: Int,
  members: Int,
  domains: Set[NormalizedHostname],
  paying: Boolean)

class OrgStatisticsCommander @Inject() (
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext,
    db: Database,
    clock: Clock,
    slackStatisticsCommander: SlackStatisticsCommander,
    orgDomainOwnCommander: OrganizationDomainOwnershipCommander,
    kifiInstallationRepo: KifiInstallationRepo,
    abook: ABookServiceClient,
    keepToLibraryRepo: KeepToLibraryRepo,
    userValueRepo: UserValueRepo,
    planManagementCommander: PlanManagementCommander,
    elizaClient: ElizaServiceClient,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgMembershipCandidateRepo: OrganizationMembershipCandidateRepo,
    orgRepo: OrganizationRepo,
    slackTeamMembershipRepo: SlackTeamMembershipRepo,
    slackTeamRepo: SlackTeamRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    userRepo: UserRepo,
    emailRepo: UserEmailAddressRepo,
    libraryRepo: LibraryRepo,
    userExperimentRepo: UserExperimentRepo,
    keepRepo: KeepRepo,
    orgChatStatsCommander: OrganizationChatStatisticsCommander,
    orgExperimentsRepo: OrganizationExperimentRepo,
    slackChannelToLibraryRepo: SlackChannelToLibraryRepo,
    userIpAddressEventLogger: UserIpAddressEventLogger,
    airbrake: AirbrakeNotifier) extends Logging {

  def organizationStatistics(orgId: Id[Organization], adminId: Id[User], numMemberRecos: Int): Future[OrganizationStatistics] = {
    val (members, candidates) = db.readOnlyReplica { implicit session =>
      val members = orgMembershipRepo.getAllByOrgId(orgId)
      val candidates = orgMembershipCandidateRepo.getAllByOrgId(orgId).toSet
      (members, candidates)
    }

    val fMemberRecommendations = try {
      abook.getRecommendationsForOrg(orgId, viewerIdOpt = None, 0, numMemberRecos + candidates.size)
    } catch {
      case ex: Exception => airbrake.notify(ex); Future.successful(Seq.empty[OrganizationInviteRecommendation])
    }

    val (org, libraries, slackToLibs, numKeeps, numKeepsLastWeek, experiments, membersStatsFut, domains, slackTeam) = db.readOnlyReplica { implicit session =>
      val org = orgRepo.get(orgId)
      val libraries = libraryRepo.getBySpace(LibrarySpace.fromOrganizationId(orgId))
      val slackToLibs = slackChannelToLibraryRepo.getAllByLibs(libraries.map(_.id.get))
      val numKeeps = libraries.map(_.keepCount).sum
      val numKeepsLastWeek = keepRepo.getCountByLibrariesSince(libraries.map(_.id.get).toSet, clock.now().minusWeeks(1))
      val userIds = members.map(_.userId) ++ candidates.map(_.userId)
      val experiments = orgExperimentsRepo.getOrganizationExperiments(orgId)
      val membersStatsFut = membersStatistics(userIds)
      val domains = orgDomainOwnCommander.getDomainsOwned(orgId)
      val slackTeam = slackTeamRepo.getByOrganizationId(orgId)
      (org, libraries, slackToLibs, numKeeps, numKeepsLastWeek, experiments, membersStatsFut, domains, slackTeam)
    }

    val (countF, botsF) = slackTeam map { team =>
      val count = slackStatisticsCommander.getTeamMembersCount(team.slackTeamId)
      val bots = slackStatisticsCommander.getSlackBots(team.slackTeamId)
      (count, bots)
    } getOrElse (Future.successful(0), Future.successful(Set.empty[String]))

    val fMemberRecoInfos = fMemberRecommendations.map(_.filter { reco =>
      reco.identifier.isLeft &&
        db.readOnlyReplica { implicit session =>
          orgMembershipRepo.getAllByUserId(reco.identifier.left.get).isEmpty &&
            orgMembershipCandidateRepo.getAllByUserId(reco.identifier.left.get).isEmpty &&
            !userExperimentRepo.hasExperiment(reco.identifier.left.get, UserExperimentType.ADMIN) &&
            !userValueRepo.getValue(reco.identifier.left.get, UserValues.ignoreForPotentialOrganizations)
        }
    }.map {
      case OrganizationInviteRecommendation(Left(userId), _, score) =>
        val (user, emailAddress) = db.readOnlyReplica { implicit session =>
          (userRepo.get(userId), Try(emailRepo.getByUser(userId)).toOption)
        }
        OrganizationMemberRecommendationInfo(user, emailAddress, math.floor(score * 10000) / 10000)
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
    val planRenewal = planManagementCommander.getPlanRenewal(orgId)
    val accountFrozen = planManagementCommander.isFrozen(orgId)
    val paymentStatus = planManagementCommander.getPaymentStatus(orgId)

    for {
      membersStats <- membersStatsFut
      memberRecos <- fMemberRecoInfos
      slackTeamMembersCount <- countF
      bots <- botsF
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
      slackStats = SlackStatistics(slackTeamMembersCount, bots, slackToLibs),
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
      paymentStatus = paymentStatus,
      planRenewal = planRenewal,
      plan = plan,
      accountFrozen = accountFrozen
    )
  }

  def organizationStatisticsOverview(org: Organization): Future[OrganizationStatisticsOverview] = {
    val orgId = org.id.get
    val (libraries, members, domains, slackChannelToLibrary, slackTeam) = db.readOnlyReplica { implicit session =>
      val members = orgMembershipRepo.countByOrgId(orgId)
      val domains = orgDomainOwnCommander.getDomainsOwned(orgId)
      val libraries = libraryRepo.getBySpace(LibrarySpace.fromOrganizationId(orgId))
      val slackChannelToLibrary = slackChannelToLibraryRepo.getAllByLibs(libraries.map(_.id.get))
      val slackTeam = slackTeamRepo.getByOrganizationId(orgId)
      (libraries, members, domains, slackChannelToLibrary, slackTeam)
    }
    val (countF, botsF) = slackTeam map { team =>
      val count = slackStatisticsCommander.getTeamMembersCount(team.slackTeamId)
      val bots = slackStatisticsCommander.getSlackBots(team.slackTeamId)
      (count, bots)
    } getOrElse (Future.successful(0), Future.successful(Set.empty[String]))

    for {
      slackTeamMembersCount <- countF
      bots <- botsF
    } yield {
      val slackStats = SlackStatistics(slackTeamMembersCount, bots, slackChannelToLibrary)
      val plan = planManagementCommander.currentPlan(orgId)
      val paying = plan.pricePerCyclePerUser.cents > 0
      val numKeeps = libraries.map(_.keepCount).sum
      OrganizationStatisticsOverview(
        org = org,
        orgId = orgId,
        pubId = Organization.publicId(orgId),
        ownerId = org.ownerId,
        handle = org.handle,
        name = org.name,
        description = org.description,
        libStats = LibCountStatistics(libraries),
        slackStats = slackStats,
        numKeeps = numKeeps,
        members = members,
        domains = domains,
        paying = paying
      )
    }
  }

  def membersStatistics(userIds: Set[Id[User]]): Future[Map[Id[User], MemberStatistics]] = {
    val onlineUsersF = elizaClient.areUsersOnline(userIds.toSeq)
    val membersStatsFut = userIds.map { userId =>
      val infoF = db.readOnlyMasterAsync { implicit s =>
        val installed = kifiInstallationRepo.all(userId).nonEmpty
        val keepVisibilityCount = keepToLibraryRepo.getPrivatePublicCountByUser(userId)
        val librariesCountsByAccess = libraryMembershipRepo.countsWithUserIdAndAccesses(userId, Set(LibraryAccess.OWNER, LibraryAccess.READ_ONLY, LibraryAccess.READ_WRITE))
        val numLibrariesCreated = librariesCountsByAccess(LibraryAccess.OWNER) // I prefer to see the Main and Secret libraries included
        val numLibrariesFollowing = librariesCountsByAccess(LibraryAccess.READ_ONLY)
        val numLibrariesCollaborating = librariesCountsByAccess(LibraryAccess.READ_WRITE)
        val dateLastManualKeep = keepRepo.latestManualKeepTime(userId)
        val user = userRepo.get(userId)
        (user, installed, keepVisibilityCount, numLibrariesCreated, numLibrariesCollaborating, numLibrariesFollowing, dateLastManualKeep)
      }
      val numChatsFut = elizaClient.getUserThreadStats(userId)
      val lastLocationF = userIpAddressEventLogger.getLastLocation(userId)
      for {
        numChats <- numChatsFut
        onlineUsers <- onlineUsersF
        lastLocation <- lastLocationF
        (user, installed, keepVisibilityCount, numLibrariesCreated, numLibrariesCollaborating, numLibrariesFollowing, dateLastManualKeep) <- infoF
      } yield {
        userId -> MemberStatistics(
          user = user,
          online = if (installed) Some(onlineUsers(userId)) else None,
          numChats = numChats.all,
          keepVisibilityCount = keepVisibilityCount,
          numLibrariesCreated = numLibrariesCreated,
          numLibrariesCollaborating = numLibrariesCollaborating,
          numLibrariesFollowing = numLibrariesFollowing,
          dateLastManualKeep = dateLastManualKeep,
          lastLocation = lastLocation
        )
      }
    }
    Future.sequence(membersStatsFut).imap(_.toMap)
  }

  def organizationStatisticsMin(org: Organization): OrganizationStatisticsMin = {
    val orgId = org.id.get
    val slackTeam = db.readOnlyReplica { implicit session => slackTeamRepo.getByOrganizationId(orgId) }
    val slackTeamSizeF = slackTeam.map { team =>
      slackStatisticsCommander.getTeamMembersCount(team.slackTeamId)
    } getOrElse Future.successful(0)

    val (mamberCount, libCount, slackLibs) = db.readOnlyReplica { implicit session =>
      val members = orgMembershipRepo.countByOrgId(orgId)
      val libraries = libraryRepo.countOrganizationLibraries(orgId)
      val slack = libraryRepo.countSlackOrganizationLibraries(orgId)
      (members, libraries, slack)
    }

    OrganizationStatisticsMin(
      org = org,
      memberCount = mamberCount,
      libCount = libCount,
      slackLibs = slackLibs,
      slackTeamSize = Await.result(slackTeamSizeF, Duration.Inf)
    )
  }

}

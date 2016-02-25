package com.keepit.commanders

import com.keepit.common.time._
import com.keepit.common.core.futureExtensionOps
import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.abook.model.OrganizationInviteRecommendation
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.eliza.ElizaServiceClient
import com.keepit.eliza.model.GroupThreadStats
import com.keepit.model._
import com.keepit.payments.PlanManagementCommander
import com.keepit.slack.SlackClient
import com.keepit.slack.models._

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.Try

case class SlackStatistics(
  activeSlackLibs: Int,
  inactiveSlackLibs: Int,
  closedSlackLibs: Int,
  brokenSlackLibs: Int,
  teamSize: Int,
  bots: Set[String])

case class OrganizationStatisticsMin(
  org: Organization,
  memberCount: Int,
  libCount: Int,
  slackLibs: Int,
  slackTeamSize: Int)

object SlackStatistics {
  def apply(teamSize: Int, bots: Set[String], slacking: Iterable[SlackChannelToLibrary]): SlackStatistics = {
    SlackStatistics(
      slacking.count { s => s.state == SlackChannelToLibraryStates.ACTIVE && s.status == SlackIntegrationStatus.On },
      slacking.count { s => s.state == SlackChannelToLibraryStates.INACTIVE },
      slacking.count { s => s.state == SlackChannelToLibraryStates.ACTIVE && s.status == SlackIntegrationStatus.Off },
      slacking.count { s => s.state == SlackChannelToLibraryStates.ACTIVE && s.status == SlackIntegrationStatus.Broken },
      teamSize, bots
    )
  }
}

class OrgStatisticsCommander @Inject() (
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext,
    db: Database,
    clock: Clock,
    orgDomainOwnCommander: OrganizationDomainOwnershipCommander,
    kifiInstallationRepo: KifiInstallationRepo,
    slackClient: SlackClient,
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
    slackTeamMembersCountCache: SlackTeamMembersCountCache,
    slackTeamMembersCache: SlackTeamMembersCache,
    slackTeamBotsCache: SlackTeamBotsCache,
    userRepo: UserRepo,
    emailRepo: UserEmailAddressRepo,
    libraryRepo: LibraryRepo,
    userExperimentRepo: UserExperimentRepo,
    keepRepo: KeepRepo,
    orgChatStatsCommander: OrganizationChatStatisticsCommander,
    orgExperimentsRepo: OrganizationExperimentRepo,
    slackChannelToLibraryRepo: SlackChannelToLibraryRepo,
    airbrake: AirbrakeNotifier) extends Logging {

  def getTeamMembersCount(slackTeamMembership: SlackTeamMembership)(implicit session: RSession): Future[Int] = {
    val count = slackTeamMembersCountCache.getOrElseFuture(SlackTeamMembersCountKey(slackTeamMembership.slackTeamId)) {
      getTeamMembers(slackTeamMembership: SlackTeamMembership).map(_.filterNot(_.bot).size)
    }
    count.recover {
      case error =>
        log.error(s"error fetching members with $slackTeamMembership", error)
        -2
    }
  }

  def getSlackBots(slackTeamMembership: SlackTeamMembership)(implicit session: RSession): Future[Set[String]] = {
    slackTeamBotsCache.getOrElseFuture(SlackTeamBotsKey(slackTeamMembership.slackTeamId)) {
      val bots = getTeamMembers(slackTeamMembership).map(_.filter(_.bot).map(_.name.value).toSet)
      bots.recover {
        case error =>
          log.error("error fetching members", error)
          Set("ERROR")
      }
    }
  }

  def getTeamMembers(slackTeamMembership: SlackTeamMembership)(implicit session: RSession): Future[Seq[SlackUserInfo]] = {
    slackTeamMembersCache.getOrElseFuture(SlackTeamMembersKey(slackTeamMembership.slackTeamId)) {
      slackClient.getUsersList(slackTeamMembership.token.get, slackTeamMembership.slackUserId).map { allMembers =>
        val deleted = allMembers.filter(_.deleted)
        val bots = allMembers.filterNot(_.deleted).filter(_.bot)
        log.info(s"fetched members from slack team ${slackTeamMembership.slackTeamName} ${slackTeamMembership.slackTeamId} via user ${slackTeamMembership.slackUsername} ${slackTeamMembership.slackUserId}; " +
          s"out of ${allMembers.size}, ${deleted.size} deleted, ${bots.size} where bots: ${bots.map(_.name)}")
        allMembers.filterNot(_.deleted)
      }
    }
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

    val (org, libraries, slackToLibs, numKeeps, numKeepsLastWeek, experiments, membersStatsFut, domains) = db.readOnlyMaster { implicit session =>
      val org = orgRepo.get(orgId)
      val libraries = libraryRepo.getBySpace(LibrarySpace.fromOrganizationId(orgId))
      val slackToLibs = slackChannelToLibraryRepo.getAllByLibs(libraries.map(_.id.get))
      val numKeeps = libraries.map(_.keepCount).sum
      val numKeepsLastWeek = keepRepo.getCountByLibrariesSince(libraries.map(_.id.get).toSet, clock.now().minusWeeks(1))
      val userIds = members.map(_.userId) ++ candidates.map(_.userId)
      val experiments = orgExperimentsRepo.getOrganizationExperiments(orgId)
      val membersStatsFut = membersStatistics(userIds)
      val domains = orgDomainOwnCommander.getDomainsOwned(orgId)
      (org, libraries, slackToLibs, numKeeps, numKeepsLastWeek, experiments, membersStatsFut, domains)
    }

    val (countF, botsF) = db.readOnlyReplica { implicit s =>
      val teams = slackTeamRepo.getByOrganizationId(orgId)
      teams flatMap { slackTeam =>
        val allMembers = slackTeamMembershipRepo.getBySlackTeam(slackTeam.slackTeamId).toSeq
        allMembers.find { _.scopes.contains(SlackAuthScope.UsersRead) }
      } map { member =>
        val count = getTeamMembersCount(member)
        val bots = getSlackBots(member)
        (count, bots)
      } getOrElse (Future.successful(if (teams.isEmpty) 0 else -1), Future.successful(Set.empty[String]))
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
    val infoF = db.readOnlyReplicaAsync { implicit session =>
      val members = orgMembershipRepo.countByOrgId(orgId)
      val domains = orgDomainOwnCommander.getDomainsOwned(orgId)
      val libraries = libraryRepo.getBySpace(LibrarySpace.fromOrganizationId(orgId))
      val slackChannelToLibrary = slackChannelToLibraryRepo.getAllByLibs(libraries.map(_.id.get))
      (libraries, members, domains, slackChannelToLibrary)
    }
    val (countF, botsF) = db.readOnlyReplica { implicit s =>
      val teams = slackTeamRepo.getByOrganizationId(orgId)
      teams flatMap { slackTeam =>
        val allMembers = slackTeamMembershipRepo.getBySlackTeam(slackTeam.slackTeamId).toSeq
        allMembers.find { _.scopes.contains(SlackAuthScope.UsersRead) }
      } map { member =>
        val count = getTeamMembersCount(member)
        val bots = getSlackBots(member)
        (count, bots)
      } getOrElse (Future.successful(if (teams.isEmpty) 0 else -1), Future.successful(Set.empty[String]))
    }
    for {
      (libraries, members, domains, slackChannelToLibrary) <- infoF
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

  def membersStatistics(userIds: Set[Id[User]])(implicit session: RSession): Future[Map[Id[User], MemberStatistics]] = {
    val onlineUsersF = elizaClient.areUsersOnline(userIds.toSeq)
    val membersStatsFut = userIds.map { userId =>
      val numChatsFut = elizaClient.getUserThreadStats(userId)
      val installed = kifiInstallationRepo.all(userId).nonEmpty
      val keepVisibilityCount = keepToLibraryRepo.getPrivatePublicCountByUser(userId)
      val librariesCountsByAccess = libraryMembershipRepo.countsWithUserIdAndAccesses(userId, Set(LibraryAccess.OWNER, LibraryAccess.READ_ONLY, LibraryAccess.READ_WRITE))
      val numLibrariesCreated = librariesCountsByAccess(LibraryAccess.OWNER) // I prefer to see the Main and Secret libraries included
      val numLibrariesFollowing = librariesCountsByAccess(LibraryAccess.READ_ONLY)
      val numLibrariesCollaborating = librariesCountsByAccess(LibraryAccess.READ_WRITE)
      val dateLastManualKeep = keepRepo.latestManualKeepTime(userId)
      val user = userRepo.get(userId)
      for {
        numChats <- numChatsFut
        onlineUsers <- onlineUsersF
      } yield {
        userId -> MemberStatistics(
          user = user,
          online = if (installed) Some(onlineUsers(userId)) else None,
          numChats = numChats.all,
          keepVisibilityCount = keepVisibilityCount,
          numLibrariesCreated = numLibrariesCreated,
          numLibrariesCollaborating = numLibrariesCollaborating,
          numLibrariesFollowing = numLibrariesFollowing,
          dateLastManualKeep = dateLastManualKeep
        )
      }
    }
    Future.sequence(membersStatsFut).imap(_.toMap)
  }

}

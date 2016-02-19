package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.abook.model.OrganizationInviteRecommendation
import com.keepit.classify.NormalizedHostname
import com.keepit.common.core.futureExtensionOps
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.common.util.DollarAmount
import com.keepit.eliza.ElizaServiceClient
import com.keepit.eliza.model.GroupThreadStats
import com.keepit.model._
import com.keepit.payments.{ PaidPlan, PaymentStatus, PlanManagementCommander }
import com.keepit.slack.models._
import com.keepit.slack._
import org.joda.time.DateTime
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future, Await }
import scala.util.Try

case class KeepVisibilityCount(secret: Int, published: Int, organization: Int, discoverable: Int) {
  def all = secret + published + organization + discoverable
}

case class UserStatistics(
  user: User,
  paying: Boolean,
  emailAddress: Option[EmailAddress],
  connections: Int,
  invitedBy: Seq[User],
  socialUsers: Seq[SocialUserInfo],
  slackMemberships: Seq[SlackTeamMembership],
  keepVisibilityCount: KeepVisibilityCount,
  experiments: Set[UserExperimentType],
  kifiInstallations: Seq[KifiInstallation],
  librariesCreated: Int,
  librariesFollowed: Int,
  dateLastManualKeep: Option[DateTime],
  orgs: Seq[OrganizationStatisticsMin],
  orgCandidates: Seq[OrganizationStatisticsMin])

case class OrganizationStatisticsMin(
  org: Organization,
  memberCount: Int,
  libCount: Int,
  slackLibs: Int)

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

case class MemberStatistics(
  user: User,
  online: Option[Boolean],
  numChats: Int,
  keepVisibilityCount: KeepVisibilityCount,
  numLibrariesCreated: Int,
  numLibrariesCollaborating: Int,
  numLibrariesFollowing: Int,
  dateLastManualKeep: Option[DateTime])

case class SlackStatistics(activeSlackLibs: Int, inactiveSlackLibs: Int, closedSlackLibs: Int, brokenSlackLibs: Int, teamSize: Int)

object SlackStatistics {
  def apply(teamSize: Int, slacking: Iterable[SlackChannelToLibrary]): SlackStatistics = {
    SlackStatistics(
      slacking.count { s => s.state == SlackChannelToLibraryStates.ACTIVE && s.status == SlackIntegrationStatus.On },
      slacking.count { s => s.state == SlackChannelToLibraryStates.INACTIVE },
      slacking.count { s => s.state == SlackChannelToLibraryStates.ACTIVE && s.status == SlackIntegrationStatus.Off },
      slacking.count { s => s.state == SlackChannelToLibraryStates.ACTIVE && s.status == SlackIntegrationStatus.Broken },
      teamSize
    )
  }
}

case class LibCountStatistics(privateLibCount: Int, protectedLibCount: Int, publicLibCount: Int)

object LibCountStatistics {
  def apply(allLibs: Iterable[Library]): LibCountStatistics = {
    val libs = allLibs.filter { l => l.kind == LibraryKind.USER_CREATED || l.kind == LibraryKind.SLACK_CHANNEL }
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

case class FullUserStatistics(
  libs: LibCountStatistics,
  slacks: SlackStatistics,
  keepCount: Int,
  manualKeepsLastWeek: Int,
  organizations: Seq[Organization],
  candidateOrganizations: Seq[Organization],
  socialUsers: Seq[SocialUserBasicInfo],
  fortyTwoConnections: Seq[User],
  kifiInstallations: Seq[KifiInstallation],
  emails: Seq[UserEmailAddress],
  invitedByUsers: Seq[User],
  paying: Boolean)

class UserStatisticsCommander @Inject() (
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext,
    db: Database,
    clock: Clock,
    kifiInstallationRepo: KifiInstallationRepo,
    slackChannelToLibraryRepo: SlackChannelToLibraryRepo,
    keepRepo: KeepRepo,
    keepToLibraryRepo: KeepToLibraryRepo,
    emailRepo: UserEmailAddressRepo,
    elizaClient: ElizaServiceClient,
    slackClient: SlackClient,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    userConnectionRepo: UserConnectionRepo,
    invitationRepo: InvitationRepo,
    userRepo: UserRepo,
    userExperimentRepo: UserExperimentRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgMembershipCandidateRepo: OrganizationMembershipCandidateRepo,
    orgExperimentsRepo: OrganizationExperimentRepo,
    orgDomainOwnCommander: OrganizationDomainOwnershipCommander,
    slackTeamMembershipRepo: SlackTeamMembershipRepo,
    slackTeamRepo: SlackTeamRepo,
    userValueRepo: UserValueRepo,
    orgChatStatsCommander: OrganizationChatStatisticsCommander,
    slackTeamMembersCountCache: SlackTeamMembersCountCache,
    slackTeamMembersCache: SlackTeamMembersCache,
    abook: ABookServiceClient,
    airbrake: AirbrakeNotifier,
    planManagementCommander: PlanManagementCommander) extends Logging {

  def invitedBy(socialUserIds: Seq[Id[SocialUserInfo]], emails: Seq[UserEmailAddress])(implicit s: RSession): Seq[User] = {
    val invites = invitationRepo.getByRecipientSocialUserIdsAndEmailAddresses(socialUserIds.toSet, emails.map(_.address).toSet)
    val inviters = invites.flatMap(_.senderUserId)
    userRepo.getAllUsers(inviters).values.toSeq
  }

  def getTeamMembersCount(slackTeamMembership: SlackTeamMembership)(implicit session: RSession): Future[Int] = {
    val count = slackTeamMembersCountCache.getOrElseFuture(SlackTeamMembersCountKey(slackTeamMembership.slackTeamId)) {
      getTeamMembers(slackTeamMembership: SlackTeamMembership).map(_.filterNot(_.bot).size)
    }
    count.recoverWith { case error =>
      log.error(s"error fetching members with $slackTeamMembership", error)
      Future.successful(-2)
    }
    count
  }

  def getSlackBots(slackTeamMembership: SlackTeamMembership)(implicit session: RSession): Future[Seq[SlackUserInfo]] = {
    val bots = getTeamMembers(slackTeamMembership).map(_.filter(_.bot))
    bots.recoverWith { case error =>
      log.error("error fetching members", error)
      Future.successful(Seq.empty)
    }
    bots
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

  def fullUserStatistics(userId: Id[User]) = {
    val (keepCount, libs, slackMembers, slackToLibs) = db.readOnlyReplica { implicit s =>
      val keepCount = keepRepo.getCountByUser(userId)
      val libs = LibCountStatistics(libraryRepo.getAllByOwner(userId))
      val slackMembers = slackTeamMembershipRepo.getByUserId(userId)
      val slackToLibs = slackChannelToLibraryRepo.getAllBySlackUserIds(slackMembers.map(_.slackUserId).toSet)
      (keepCount, libs, slackMembers, slackToLibs)
    }
    val slackTeamMembersCountF = db.readOnlyReplica { implicit s =>
      val slackTeamMembersCounts = slackMembers.filter { _.scopes.contains(SlackAuthScope.UsersRead) }.map(getTeamMembersCount)
      Future.sequence(slackTeamMembersCounts).map(_.sum)
    }
    val infoF = db.readOnlyReplicaAsync { implicit s =>
      val manualKeepsLastWeek = keepRepo.getCountManualByUserInLastDays(userId, 7) //last seven days
      val organizations = orgRepo.getByIds(orgMembershipRepo.getAllByUserId(userId).map(_.organizationId).toSet).values.toList.filter(_.state == OrganizationStates.ACTIVE)
      val candidateOrganizations = orgRepo.getByIds(orgMembershipCandidateRepo.getAllByUserId(userId).map(_.organizationId).toSet).values.toList.filter(_.state == OrganizationStates.ACTIVE)
      val socialUsers = socialUserInfoRepo.getSocialUserBasicInfosByUser(userId)
      val fortyTwoConnections = userConnectionRepo.getConnectedUsers(userId).map { userId =>
        userRepo.get(userId)
      }.toSeq.sortBy(u => s"${u.firstName} ${u.lastName}")
      val kifiInstallations = kifiInstallationRepo.all(userId).sortWith((a, b) => b.updatedAt.isBefore(a.updatedAt)).take(10)
      val emails = emailRepo.getAllByUser(userId)
      val invitedByUsers = invitedBy(socialUsers.map(_.id), emails)
      val paying = organizations.exists { org =>
        val plan = planManagementCommander.currentPlan(org.id.get)
        plan.pricePerCyclePerUser.cents > 0
      }
      (manualKeepsLastWeek, organizations, candidateOrganizations, socialUsers, fortyTwoConnections, kifiInstallations, emails, invitedByUsers, paying)
    }
    for {
      slackTeamMembersCount <- slackTeamMembersCountF
      (manualKeepsLastWeek, organizations, candidateOrganizations, socialUsers, fortyTwoConnections, kifiInstallations, emails, invitedByUsers, paying) <- infoF
    } yield {
      val slacks = SlackStatistics(slackTeamMembersCount, slackToLibs)
      FullUserStatistics(libs, slacks, keepCount, manualKeepsLastWeek, organizations, candidateOrganizations, socialUsers, fortyTwoConnections, kifiInstallations, emails, invitedByUsers, paying)
    }
  }

  def userStatistics(user: User, socialUserInfos: Map[Id[User], Seq[SocialUserInfo]])(implicit s: RSession): UserStatistics = {
    val kifiInstallations = kifiInstallationRepo.all(user.id.get).sortWith((a, b) => b.updatedAt.isBefore(a.updatedAt)).take(3)
    val keepVisibilityCount = keepToLibraryRepo.getPrivatePublicCountByUser(user.id.get)
    val emails = emailRepo.getAllByUser(user.id.get)
    val emailAddress = Try(emailRepo.getByUser(user.id.get)).toOption
    val librariesCountsByAccess = libraryMembershipRepo.countsWithUserIdAndAccesses(user.id.get, Set(LibraryAccess.OWNER, LibraryAccess.READ_ONLY))
    val librariesCreated = librariesCountsByAccess(LibraryAccess.OWNER) - 2 //ignoring main and secret
    val librariesFollowed = librariesCountsByAccess(LibraryAccess.READ_ONLY)
    val latestManualKeepTime = keepRepo.latestManualKeepTime(user.id.get)
    val orgs = orgRepo.getByIds(orgMembershipRepo.getAllByUserId(user.id.get).map(_.organizationId).toSet).values.toList
    val orgsStats = orgs.map(o => organizationStatisticsMin(o))
    val orgCandidates = orgRepo.getByIds(orgMembershipCandidateRepo.getAllByUserId(user.id.get).map(_.organizationId).toSet).values.toList
    val orgCandidatesStats = orgCandidates.map(o => organizationStatisticsMin(o))
    val slackMemberships = slackTeamMembershipRepo.getByUserId(user.id.get)
    val paying = orgs.exists { org =>
      val plan = planManagementCommander.currentPlan(org.id.get)
      plan.pricePerCyclePerUser.cents > 0
    }

    UserStatistics(
      user,
      paying = paying,
      emailAddress,
      userConnectionRepo.getConnectionCount(user.id.get),
      invitedBy(socialUserInfos.getOrElse(user.id.get, Seq()).map(_.id.get), emails),
      socialUserInfos.getOrElse(user.id.get, Seq()),
      slackMemberships,
      keepVisibilityCount,
      userExperimentRepo.getUserExperiments(user.id.get),
      kifiInstallations,
      librariesCreated,
      librariesFollowed,
      latestManualKeepTime,
      orgsStats,
      orgCandidatesStats
    )
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

    val slackTeamMembersCountF = db.readOnlyMaster { implicit s =>
      val count = slackTeamRepo.getByOrganizationId(orgId) flatMap { slackTeam =>
        val slackMembers = slackTeamMembershipRepo.getBySlackTeam(slackTeam.slackTeamId)
        slackMembers.find { _.scopes.contains(SlackAuthScope.UsersRead) }.map(getTeamMembersCount)
      } getOrElse Future.successful(-1)
      count
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
      slackTeamMembersCount <- slackTeamMembersCountF
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
      slackStats = SlackStatistics(slackTeamMembersCount, slackToLibs),
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
    val slackTeamMembersCountF = db.readOnlyReplica { implicit s =>
      slackTeamRepo.getByOrganizationId(orgId) flatMap { slackTeam =>
        val slackMembers = slackTeamMembershipRepo.getBySlackTeam(slackTeam.slackTeamId)
        slackMembers.find { _.scopes.contains(SlackAuthScope.UsersRead) }.map(getTeamMembersCount)
      } getOrElse Future.successful(-1)
    }
    for {
      (libraries, members, domains, slackChannelToLibrary) <- infoF
      slackTeamMembersCount <- slackTeamMembersCountF

    } yield {
      val slackStats = SlackStatistics(slackTeamMembersCount, slackChannelToLibrary)
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

  def organizationStatisticsMin(org: Organization): OrganizationStatisticsMin = {
    val orgId = org.id.get
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
      slackLibs = slackLibs
    )
  }

}

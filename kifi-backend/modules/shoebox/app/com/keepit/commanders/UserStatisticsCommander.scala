package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.payments.PlanManagementCommander
import com.keepit.slack.models._
import com.keepit.slack._
import play.api.libs.json.Json
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future, Await }
import scala.util.Try

case class KeepVisibilityCount(secret: Int, published: Int, organization: Int, discoverable: Int) {
  def all = secret + published + organization + discoverable
}

case class KifiInstallations(firefox: Boolean, chrome: Boolean, safari: Boolean, yandex: Boolean, iphone: Boolean, android: Boolean, windows: Boolean, mac: Boolean, linux: Boolean) {
  def exist: Boolean = firefox || chrome || safari || yandex || iphone || android
  def isEmpty: Boolean = !exist
}

object KifiInstallations {
  def apply(all: Seq[KifiInstallation]): KifiInstallations = {
    val agents = all.map(_.userAgent)
    KifiInstallations(
      firefox = agents.exists(_.name.toLowerCase.contains("firefox")),
      chrome = agents.exists(_.name.toLowerCase.contains("chrome")),
      safari = agents.exists(_.name.toLowerCase.contains("safari")),
      yandex = agents.exists(_.name.toLowerCase.contains("yabrowser")),
      iphone = agents.exists(_.isIphone),
      android = agents.exists(_.isAndroid),
      windows = agents.exists(_.operatingSystemFamily.toLowerCase.contains("window")),
      mac = agents.exists(_.operatingSystemFamily.toLowerCase.contains("os x")),
      linux = agents.exists(_.operatingSystemFamily.toLowerCase.contains("linux"))
    )
  }
}

case class UserStatistics(
  user: User,
  paying: Boolean,
  emailAddress: Option[EmailAddress],
  invitedBy: Seq[User],
  socialUsers: Seq[SocialUserInfo],
  slackMemberships: Seq[SlackTeamMembership],
  keepVisibilityCount: KeepVisibilityCount,
  kifiInstallations: KifiInstallations,
  librariesCreated: Int,
  librariesFollowed: Int,
  orgs: Seq[OrganizationStatisticsMin],
  orgCandidates: Seq[OrganizationStatisticsMin],
  lastLocation: Option[RichIpAddress])

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
  paying: Boolean,
  lastLocation: Option[RichIpAddress])

class UserStatisticsCommander @Inject() (
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext,
    db: Database,
    clock: Clock,
    libraryMembershipRepo: LibraryMembershipRepo,
    slackStatisticsCommander: SlackStatisticsCommander,
    kifiInstallationRepo: KifiInstallationRepo,
    slackChannelToLibraryRepo: SlackChannelToLibraryRepo,
    keepRepo: KeepRepo,
    keepToLibraryRepo: KeepToLibraryRepo,
    emailRepo: UserEmailAddressRepo,
    libraryRepo: LibraryRepo,
    userConnectionRepo: UserConnectionRepo,
    invitationRepo: InvitationRepo,
    userRepo: UserRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgMembershipCandidateRepo: OrganizationMembershipCandidateRepo,
    slackTeamMembershipRepo: SlackTeamMembershipRepo,
    slackTeamRepo: SlackTeamRepo,
    orgStatisticsCommander: OrgStatisticsCommander,
    airbrake: AirbrakeNotifier,
    userValueRepo: UserValueRepo,
    planManagementCommander: PlanManagementCommander) extends Logging {

  def invitedBy(socialUserIds: Seq[Id[SocialUserInfo]], emails: Seq[UserEmailAddress])(implicit s: RSession): Seq[User] = {
    val invites = invitationRepo.getByRecipientSocialUserIdsAndEmailAddresses(socialUserIds.toSet, emails.map(_.address).toSet)
    val inviters = invites.flatMap(_.senderUserId)
    userRepo.getAllUsers(inviters).values.toSeq
  }

  def getLastLocation(userId: Id[User])(implicit session: RSession) = {
    userValueRepo.getUserValue(userId, UserValueName.LAST_RECORDED_LOCATION) flatMap { locationValue =>
      RichIpAddress.format.reads(Json.parse(locationValue.value)).asOpt
    }
  }

  def fullUserStatistics(userId: Id[User]) = {
    val (keepCount, libs, slackMembers, slackToLibs, lastLocation) = db.readOnlyReplica { implicit s =>
      val keepCount = keepRepo.getCountByUser(userId)
      val libs = LibCountStatistics(libraryRepo.getAllByOwner(userId))
      val slackMembers = slackTeamMembershipRepo.getByUserId(userId)
      val slackToLibs = slackChannelToLibraryRepo.getAllBySlackUserIds(slackMembers.map(_.slackUserId).toSet)
      val lastLocation: Option[RichIpAddress] = None //getLastLocation(userId)
      (keepCount, libs, slackMembers, slackToLibs, lastLocation)
    }
    val (countF, botsF) = db.readOnlyReplica { implicit s =>
      val members = slackMembers.filter { _.scopes.contains(SlackAuthScope.UsersRead) }
      val slackTeamMembersCounts = members.map(slackStatisticsCommander.getTeamMembersCount)
      val count = Future.sequence(slackTeamMembersCounts).map(_.sum)
      val bots = Future.sequence(members.map(slackStatisticsCommander.getSlackBots)).map(_.flatten.toSet)
      (count, bots)
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
      slackTeamMembersCount <- countF
      bots <- botsF
      (manualKeepsLastWeek, organizations, candidateOrganizations, socialUsers, fortyTwoConnections, kifiInstallations, emails, invitedByUsers, paying) <- infoF
    } yield {
      val slacks = SlackStatistics(slackTeamMembersCount, bots, slackToLibs)
      FullUserStatistics(libs, slacks, keepCount, manualKeepsLastWeek, organizations, candidateOrganizations, socialUsers, fortyTwoConnections, kifiInstallations, emails, invitedByUsers, paying, lastLocation)
    }
  }

  def userStatistics(user: User, socialUserInfos: Map[Id[User], Seq[SocialUserInfo]])(implicit s: RSession): UserStatistics = {
    val kifiInstallations = KifiInstallations(kifiInstallationRepo.all(user.id.get))
    val keepVisibilityCount = keepToLibraryRepo.getPrivatePublicCountByUser(user.id.get)
    val emails = emailRepo.getAllByUser(user.id.get)
    val emailAddress = Try(emailRepo.getByUser(user.id.get)).toOption
    val librariesCountsByAccess = libraryMembershipRepo.countsWithUserIdAndAccesses(user.id.get, Set(LibraryAccess.OWNER, LibraryAccess.READ_ONLY))
    val librariesCreated = librariesCountsByAccess(LibraryAccess.OWNER) - 2 //ignoring main and secret
    val librariesFollowed = librariesCountsByAccess(LibraryAccess.READ_ONLY)
    val orgs = orgRepo.getByIds(orgMembershipRepo.getAllByUserId(user.id.get).map(_.organizationId).toSet).values.toList
    val orgsStats = orgs.map(o => organizationStatisticsMin(o))
    val orgCandidates = orgRepo.getByIds(orgMembershipCandidateRepo.getAllByUserId(user.id.get).map(_.organizationId).toSet).values.toList
    val orgCandidatesStats = orgCandidates.map(o => organizationStatisticsMin(o))
    val slackMemberships = slackTeamMembershipRepo.getByUserId(user.id.get)
    val paying = orgs.exists { org =>
      val plan = planManagementCommander.currentPlan(org.id.get)
      plan.pricePerCyclePerUser.cents > 0
    }
    val lastLocation = getLastLocation(user.id.get)

    UserStatistics(
      user,
      paying = paying,
      emailAddress,
      invitedBy(socialUserInfos.getOrElse(user.id.get, Seq()).map(_.id.get), emails),
      socialUserInfos.getOrElse(user.id.get, Seq()),
      slackMemberships,
      keepVisibilityCount,
      kifiInstallations,
      librariesCreated,
      librariesFollowed,
      orgsStats,
      orgCandidatesStats,
      lastLocation
    )
  }

  def organizationStatisticsMin(org: Organization): OrganizationStatisticsMin = {
    val orgId = org.id.get
    val slackTeamSizeF = db.readOnlyReplica { implicit s =>
      val teams = slackTeamRepo.getByOrganizationId(orgId)
      teams flatMap { slackTeam =>
        val allMembers = slackTeamMembershipRepo.getBySlackTeam(slackTeam.slackTeamId).toSeq
        allMembers.find {
          _.scopes.contains(SlackAuthScope.UsersRead)
        }
      } map { member =>
        slackStatisticsCommander.getTeamMembersCount(member)
      } getOrElse Future.successful(if (teams.isEmpty) 0 else -1)
    }

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

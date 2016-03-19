package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.core.{ anyExtensionOps, _ }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.SlackLog
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.{ Clock, _ }
import com.keepit.common.util.{ LinkElement, DescriptionElements }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.slack.models._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[SlackTeamCommanderImpl])
trait SlackTeamCommander {
  def getSlackTeams(userId: Id[User]): Set[SlackTeam]
  def getSlackTeamOpt(slackTeamId: SlackTeamId): Option[SlackTeam]
  def setupSlackTeam(userId: Id[User], slackTeamId: SlackTeamId, organizationId: Option[Id[Organization]])(implicit context: HeimdalContext): Future[SlackTeam]
  def addSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[(SlackTeam, Boolean)]
  def createOrganizationForSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[SlackTeam]
  def connectSlackTeamToOrganization(userId: Id[User], slackTeamId: SlackTeamId, organizationId: Id[Organization])(implicit context: HeimdalContext): Try[SlackTeam]
  def getOrganizationsToConnectToSlackTeam(userId: Id[User])(implicit session: RSession): Set[BasicOrganization]
  def turnCommentMirroring(userId: Id[User], slackTeamId: SlackTeamId, turnOn: Boolean): Try[Id[Organization]]
  def togglePersonalDigests(userId: Id[User], slackTeamId: SlackTeamId, slackUserId: SlackUserId, turnOn: Boolean): Future[Unit]
  def unsafeTogglePersonalDigests(slackTeamId: SlackTeamId, slackUserId: SlackUserId, turnOn: Boolean): Future[Unit]
}

@Singleton
class SlackTeamCommanderImpl @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackClient: SlackClientWrapper,
  permissionCommander: PermissionCommander,
  orgCommander: OrganizationCommander,
  pathCommander: PathCommander,
  orgAvatarCommander: OrganizationAvatarCommander,
  orgMembershipRepo: OrganizationMembershipRepo,
  organizationInfoCommander: OrganizationInfoCommander,
  basicUserRepo: BasicUserRepo,
  clock: Clock,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends SlackTeamCommander {
  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SLACK)

  def getSlackTeams(userId: Id[User]): Set[SlackTeam] = {
    db.readOnlyMaster { implicit session =>
      val slackTeamIds = slackTeamMembershipRepo.getByUserId(userId).map(_.slackTeamId).toSet
      slackTeamRepo.getBySlackTeamIds(slackTeamIds).values.toSet
    }
  }

  def getSlackTeamOpt(slackTeamId: SlackTeamId): Option[SlackTeam] = db.readOnlyMaster(implicit s => slackTeamRepo.getBySlackTeamIdNoCache(slackTeamId))

  def setupSlackTeam(userId: Id[User], slackTeamId: SlackTeamId, organizationId: Option[Id[Organization]])(implicit context: HeimdalContext): Future[SlackTeam] = {
    val (slackTeam, connectableOrgs) = db.readWrite { implicit session =>
      (slackTeamRepo.getBySlackTeamIdNoCache(slackTeamId).get, getOrganizationsToConnectToSlackTeam(userId))
    }
    organizationId match {
      case Some(orgId) => Future.fromTry(connectSlackTeamToOrganization(userId, slackTeamId, orgId))
      case None if slackTeam.organizationId.isEmpty && connectableOrgs.isEmpty => createOrganizationForSlackTeam(userId, slackTeamId)
      case _ => Future.successful(slackTeam)
    }
  }

  def addSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[(SlackTeam, Boolean)] = {
    val (teamOpt, connectableOrgs) = db.readOnlyMaster { implicit session =>
      (slackTeamRepo.getBySlackTeamIdNoCache(slackTeamId), getOrganizationsToConnectToSlackTeam(userId))
    }
    teamOpt match {
      case Some(team) if team.organizationId.isEmpty && connectableOrgs.isEmpty => createOrganizationForSlackTeam(userId, slackTeamId).imap((_, true))
      case Some(team) => Future.successful((team, false))
      case None => Future.failed(SlackActionFail.TeamNotFound(slackTeamId))
    }
  }

  def createOrganizationForSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[SlackTeam] = {
    val (slackTeamOpt, membershipOpt) = db.readOnlyMaster { implicit session =>
      val slackTeamOpt = slackTeamRepo.getBySlackTeamIdNoCache(slackTeamId)
      val membershipOpt = slackTeamMembershipRepo.getByUserIdAndSlackTeam(userId, slackTeamId)
      (slackTeamOpt, membershipOpt)
    }

    slackTeamOpt match {
      case Some(team) => team.organizationId match {
        case None => membershipOpt match {
          case Some(membership) =>
            val preferredTokens = Seq(team.getKifiBotTokenIncludingScopes(SlackAuthScope.teamSetup), membership.getTokenIncludingScopes(SlackAuthScope.teamSetup)).flatten
            slackClient.getTeamInfo(slackTeamId, preferredTokens).flatMap { teamInfo =>
              val orgInitialValues = OrganizationInitialValues(name = teamInfo.name.value, description = None)
              orgCommander.createOrganization(OrganizationCreateRequest(userId, orgInitialValues)) match {
                case Right(createdOrg) =>
                  val orgId = createdOrg.newOrg.id.get
                  val futureAvatar = teamInfo.icon.maxByOpt(_._1) match {
                    case None => Future.successful(())
                    case Some((_, imageUrl)) => orgAvatarCommander.persistRemoteOrganizationAvatars(orgId, imageUrl).imap(_ => ())
                  }
                  teamInfo.emailDomains.exists { domain =>
                    orgCommander.modifyOrganization(OrganizationModifyRequest(userId, orgId, OrganizationModifications(rawSite = Some(domain.value)))).isRight
                  }
                  val connectedTeamMaybe = connectSlackTeamToOrganization(userId, slackTeamId, orgId)
                  futureAvatar.flatMap { _ =>
                    Future.fromTry {
                      connectedTeamMaybe
                    }
                  }

                case Left(error) => Future.failed(error)
              }
            }
          case None => Future.failed(SlackActionFail.InvalidMembership(userId, team.slackTeamId, team.slackTeamName, membershipOpt))
        }
        case Some(orgId) => Future.failed(SlackActionFail.TeamAlreadyConnected(team.slackTeamId, team.slackTeamName, orgId))
      }
      case None => Future.failed(SlackActionFail.TeamNotFound(slackTeamId))
    }
  }

  def connectSlackTeamToOrganization(userId: Id[User], slackTeamId: SlackTeamId, newOrganizationId: Id[Organization])(implicit context: HeimdalContext): Try[SlackTeam] = {
    db.readWrite { implicit session =>
      permissionCommander.getOrganizationPermissions(newOrganizationId, Some(userId)).contains(SlackIdentityCommander.slackSetupPermission) match {
        case false => Failure(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        case true => slackTeamRepo.getBySlackTeamIdNoCache(slackTeamId) match {
          case None => Failure(SlackActionFail.TeamNotFound(slackTeamId))
          case Some(team) => team.organizationId match {
            case Some(connectedOrg) if connectedOrg == newOrganizationId => Success(team)
            case Some(otherOrg) => Failure(SlackActionFail.TeamAlreadyConnected(team.slackTeamId, team.slackTeamName, team.organizationId.get))
            case None => slackTeamRepo.getByOrganizationId(newOrganizationId) match {
              case Some(orgTeam) => Failure(SlackActionFail.OrgAlreadyConnected(newOrganizationId, orgTeam.slackTeamId, failedToConnectTeam = slackTeamId))
              case None => slackTeamMembershipRepo.getByUserIdAndSlackTeam(userId, slackTeamId) match {
                case None => Failure(SlackActionFail.InvalidMembership(userId, team.slackTeamId, team.slackTeamName, None))
                case Some(validMembership) => Success(slackTeamRepo.save(team.withOrganizationId(Some(newOrganizationId))))
              }
            }
          }
        }
      }
    } tap {
      case Success(team) => slackClient.getUsers(team.slackTeamId).imap(Some(_)).recover { case _ => None }.map { slackTeamMembersOpt =>
        val numMembersOpt = slackTeamMembersOpt.map(_.count(member => !member.deleted && !member.bot))
        val (user, org) = db.readOnlyMaster { implicit session =>
          (basicUserRepo.load(userId), organizationInfoCommander.getBasicOrganizationHelper(newOrganizationId))
        }
        inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(
          user, "connected Slack team", team.slackTeamName.value, numMembersOpt.map(numMembers => s"with $numMembers members"), "to Kifi org", org
        )))
      }

      case Failure(fail) =>
        slackLog.warn(s"Failed to connect $slackTeamId to org $newOrganizationId for user $userId because:", fail.getMessage)
    }
  }

  def getOrganizationsToConnectToSlackTeam(userId: Id[User])(implicit session: RSession): Set[BasicOrganization] = {
    val allOrgIds = orgMembershipRepo.getAllByUserId(userId).map(_.organizationId).toSet
    val permissionsByOrgIds = permissionCommander.getOrganizationsPermissions(allOrgIds, Some(userId))
    val slackTeamsByOrgId = slackTeamRepo.getByOrganizationIds(allOrgIds)
    val validOrgIds = allOrgIds.filter(orgId => slackTeamsByOrgId(orgId).isEmpty && permissionsByOrgIds(orgId).contains(SlackIdentityCommander.slackSetupPermission))
    organizationInfoCommander.getBasicOrganizations(validOrgIds).values.toSet
  }

  def turnCommentMirroring(userId: Id[User], slackTeamId: SlackTeamId, turnOn: Boolean): Try[Id[Organization]] = db.readWrite { implicit session =>
    slackTeamRepo.getBySlackTeamIdNoCache(slackTeamId) match {
      case Some(team) =>
        team.organizationId match {
          case Some(orgId) =>
            val hasOrgPermissions = permissionCommander.getOrganizationPermissions(orgId, Some(userId)).contains(SlackIdentityCommander.slackSetupPermission)
            if (hasOrgPermissions) {
              val updatedSetting = if (turnOn) StaticFeatureSetting.ENABLED else StaticFeatureSetting.DISABLED
              orgCommander.unsafeSetAccountFeatureSettings(orgId, OrganizationSettings(Map(StaticFeature.SlackCommentMirroring -> updatedSetting)), Some(userId))
              Success(orgId)
            } else Failure(OrganizationFail.INSUFFICIENT_PERMISSIONS)

          case None => Failure(SlackActionFail.TeamNotConnected(team.slackTeamId, team.slackTeamName))
        }
      case None => Failure(SlackActionFail.TeamNotFound(slackTeamId))
    }
  }
  def togglePersonalDigests(userId: Id[User], slackTeamId: SlackTeamId, slackUserId: SlackUserId, turnOn: Boolean): Future[Unit] = {
    val membership = db.readOnlyReplica(implicit s => slackTeamMembershipRepo.getBySlackTeamAndUser(slackTeamId, slackUserId))
    if (!membership.exists(_.userId.contains(userId))) Future.successful(SlackFail.NoSuchMembership)
    else unsafeTogglePersonalDigests(slackTeamId, slackUserId, turnOn)
  }

  def unsafeTogglePersonalDigests(slackTeamId: SlackTeamId, slackUserId: SlackUserId, turnOn: Boolean): Future[Unit] = {
    val now = clock.now
    val membershipOpt = db.readWrite { implicit s =>
      slackTeamMembershipRepo.getBySlackTeamAndUser(slackTeamId, slackUserId).map { membership =>
        val updated = if (turnOn) {
          membership.withNextPersonalDigestAt(now)
        } else {
          membership.withNoNextPersonalDigest
        }
        if (updated != membership) slackTeamMembershipRepo.save(updated) else membership
      }
    }
    import DescriptionElements._
    membershipOpt.fold(Future.failed[Unit](SlackFail.NoSuchMembership(slackTeamId, slackUserId))) { membership =>
      val channelId = membership.slackUserId.asChannel
      val trackingParams = SlackAnalytics.generateTrackingParams(channelId, NotificationCategory.NonUser.SETTINGS_TOGGLE, Some((!turnOn).toString))
      def toggleLink(on: Boolean) = LinkElement(pathCommander.slackPersonalDigestToggle(slackTeamId, slackUserId, turnOn = on).withQuery(trackingParams))
      slackClient.sendToSlackHoweverPossible(membership.slackTeamId, channelId, SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(
        if (turnOn) DescriptionElements(
          ":tada: Thanks for having me back! I'll gather some of your stats and update you about once a week if I have things to share.",
          "If you want to power me back down, you can silence me", "here" --> toggleLink(false), "."
        )
        else DescriptionElements(
          ":+1: Roger that, I'll keep quiet from here on out.",
          "If you'd like to hear from me again, you can power my notifications back on", "here" --> toggleLink(true), "."
        )
      ))).map(_ => ())
    }
  }
}

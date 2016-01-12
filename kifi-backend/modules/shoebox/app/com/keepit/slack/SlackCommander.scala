package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time._
import com.keepit.common.util.{ DescriptionElements, LinkElement }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.slack.models._
import org.joda.time.Period
import play.api.http.Status._
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

object SlackCommander {
  val slackSetupPermission = OrganizationPermission.EDIT_ORGANIZATION
  val minPeriodBetweenDigestNotifications = Period.minutes(1) // TODO(ryan): make this way slower
}

@ImplementedBy(classOf[SlackCommanderImpl])
trait SlackCommander {
  // Open their own DB sessions, intended to be called directly from controllers
  def registerAuthorization(userId: Id[User], auth: SlackAuthorizationResponse, identity: SlackIdentifyResponse): Unit
  def setupIntegrations(userId: Id[User], libId: Id[Library], webhook: SlackIncomingWebhook, identity: SlackIdentifyResponse): Unit
  def turnOnLibraryPush(integrationId: Id[LibraryToSlackChannel], webhook: SlackIncomingWebhook, identity: SlackIdentifyResponse): Id[Library]
  def turnOnChannelIngestion(integrationId: Id[SlackChannelToLibrary], identity: SlackIdentifyResponse): Id[Library]
  def modifyIntegrations(request: SlackIntegrationModifyRequest): Try[SlackIntegrationModifyResponse]
  def deleteIntegrations(request: SlackIntegrationDeleteRequest): Try[SlackIntegrationDeleteResponse]

  def fetchMissingChannelIds(): Future[Unit]

  def setupSlackTeam(userId: Id[User], identity: SlackIdentifyResponse, organizationId: Option[Id[Organization]])(implicit context: HeimdalContext): Future[SlackTeam]
  def createOrganizationForSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[SlackTeam]
  def connectSlackTeamToOrganization(userId: Id[User], slackTeamId: SlackTeamId, organizationId: Id[Organization]): Try[SlackTeam]
  def getOrganizationsToConnect(userId: Id[User]): Map[Id[Organization], OrganizationInfo]
  def setupSlackChannel(team: SlackTeam, membership: SlackTeamMembership, channel: SlackChannelInfo)(implicit context: HeimdalContext): Either[LibraryFail, Library]
  def setupLatestSlackChannels(userId: Id[User], teamId: SlackTeamId)(implicit context: HeimdalContext): Future[Map[SlackChannel, Either[LibraryFail, Library]]]
  def pushDigestNotificationsForRipeTeams(): Future[Unit]
}

@Singleton
class SlackCommanderImpl @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackIncomingWebhookInfoRepo: SlackIncomingWebhookInfoRepo,
  channelToLibRepo: SlackChannelToLibraryRepo,
  libToChannelRepo: LibraryToSlackChannelRepo,
  slackClient: SlackClientWrapper,
  libToSlackPusher: LibraryToSlackChannelPusher,
  ingestionCommander: SlackIngestionCommander,
  basicUserRepo: BasicUserRepo,
  pathCommander: PathCommander,
  permissionCommander: PermissionCommander,
  orgCommander: OrganizationCommander,
  orgDomainCommander: OrganizationDomainOwnershipCommander,
  orgAvatarCommander: OrganizationAvatarCommander,
  libraryCommander: LibraryCommander,
  libRepo: LibraryRepo,
  ktlRepo: KeepToLibraryRepo,
  keepRepo: KeepRepo,
  attributionRepo: KeepSourceAttributionRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  orgMembershipCommander: OrganizationMembershipCommander,
  organizationInfoCommander: OrganizationInfoCommander,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  inhouseSlackClient: InhouseSlackClient)
    extends SlackCommander with Logging {

  def registerAuthorization(userId: Id[User], auth: SlackAuthorizationResponse, identity: SlackIdentifyResponse): Unit = {
    require(auth.teamId == identity.teamId && auth.teamName == identity.teamName)
    db.readWrite { implicit s =>
      slackTeamMembershipRepo.internMembership(SlackTeamMembershipInternRequest(
        userId = Some(userId),
        slackUserId = identity.userId,
        slackUsername = identity.userName,
        slackTeamId = auth.teamId,
        slackTeamName = auth.teamName,
        token = auth.accessToken,
        scopes = auth.scopes
      ))
      auth.incomingWebhook.foreach { webhook =>
        slackIncomingWebhookInfoRepo.save(SlackIncomingWebhookInfo(
          slackUserId = identity.userId,
          slackTeamId = identity.teamId,
          slackChannelId = None,
          webhook = webhook,
          lastPostedAt = None
        ))
      }
      slackTeamRepo.getBySlackTeamId(auth.teamId).foreach { team =>
        team.organizationId.foreach { orgId =>
          if (orgMembershipRepo.getByOrgIdAndUserId(orgId, userId).isEmpty) {
            orgMembershipCommander.unsafeAddMembership(OrganizationMembershipAddRequest(orgId, userId, userId))
          }
        }
      }
    }
  }

  def setupIntegrations(userId: Id[User], libId: Id[Library], webhook: SlackIncomingWebhook, identity: SlackIdentifyResponse): Unit = {
    db.readWrite { implicit s =>
      val defaultSpace = libRepo.get(libId).organizationId match {
        case Some(orgId) if orgMembershipRepo.getByOrgIdAndUserId(orgId, userId).isDefined =>
          LibrarySpace.fromOrganizationId(orgId)
        case _ => LibrarySpace.fromUserId(userId)
      }
      libToChannelRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
        requesterId = userId,
        space = defaultSpace,
        libraryId = libId,
        slackUserId = identity.userId,
        slackTeamId = identity.teamId,
        slackChannelId = None,
        slackChannelName = webhook.channelName,
        status = SlackIntegrationStatus.On
      ))
      channelToLibRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
        requesterId = userId,
        space = defaultSpace,
        libraryId = libId,
        slackUserId = identity.userId,
        slackTeamId = identity.teamId,
        slackChannelId = None,
        slackChannelName = webhook.channelName,
        status = SlackIntegrationStatus.Off
      ))
    }

    SafeFuture {
      val welcomeMsg = db.readOnlyMaster { implicit s =>
        import DescriptionElements._
        val lib = libRepo.get(libId)
        DescriptionElements(
          "A new Kifi integration was just set up.",
          "Keeps from", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute), "will be posted to this channel."
        )
      }
      slackClient.sendToSlack(identity.userId, identity.teamId, webhook.channelName, SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(welcomeMsg)).quiet)
        .foreach { _ =>
          libToSlackPusher.pushUpdatesToSlack(libId)
            .foreach { _ => fetchMissingChannelIds() }
        }

      val inhouseMsg = db.readOnlyReplica { implicit s =>
        import DescriptionElements._
        val lib = libRepo.get(libId)
        val user = basicUserRepo.load(userId)
        val orgOpt = lib.organizationId.flatMap(organizationInfoCommander.getBasicOrganizationHelper)
        DescriptionElements(
          user, s"set up Slack integrations between channel ${webhook.channelName.value} and",
          lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute),
          orgOpt.map(org => DescriptionElements("in", org.name --> LinkElement(pathCommander.orgPage(org))))
        )
      }
      inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(inhouseMsg))
    }
  }

  private def validateRequest(request: SlackIntegrationRequest)(implicit session: RSession): Option[LibraryFail] = {
    request match {
      case r: SlackIntegrationCreateRequest =>
        val userHasAccessToSpace = r.space match {
          case UserSpace(uid) => r.requesterId == uid
          case OrganizationSpace(orgId) => orgMembershipRepo.getByOrgIdAndUserId(orgId, r.requesterId).isDefined
        }
        if (!userHasAccessToSpace) Some(LibraryFail(FORBIDDEN, "insufficient_permissions_for_target_space"))
        else None

      case r: SlackIntegrationModifyRequest =>
        val toSlacks = libToChannelRepo.getActiveByIds(r.libToSlack.keySet)
        val fromSlacks = channelToLibRepo.getActiveByIds(r.slackToLib.keySet)
        val oldSpaces = fromSlacks.map(_.space) ++ toSlacks.map(_.space)
        val newSpaces = (r.libToSlack.values.flatMap(_.space) ++ r.slackToLib.values.flatMap(_.space)).toSet
        val spacesUserCannotAccess = (oldSpaces ++ newSpaces).filter {
          case UserSpace(uid) => r.requesterId != uid
          case OrganizationSpace(orgId) => orgMembershipRepo.getByOrgIdAndUserId(orgId, r.requesterId).isEmpty
        }
        def userCanWriteToIngestingLibraries = r.slackToLib.filter {
          case (stlId, mod) => mod.status.contains(SlackIntegrationStatus.On)
        }.forall {
          case (stlId, mod) =>
            fromSlacks.find(_.id.get == stlId).exists { stl =>
              // TODO(ryan): I don't know if it's a good idea to ignore permissions if the integration is already on...
              stl.status == SlackIntegrationStatus.On || permissionCommander.getLibraryPermissions(stl.libraryId, Some(r.requesterId)).contains(LibraryPermission.ADD_KEEPS)
            }
        }
        Stream(
          (oldSpaces intersect spacesUserCannotAccess).nonEmpty -> LibraryFail(FORBIDDEN, "permission_to_modify_denied"),
          (newSpaces intersect spacesUserCannotAccess).nonEmpty -> LibraryFail(FORBIDDEN, "illegal_destination_space"),
          !userCanWriteToIngestingLibraries -> LibraryFail(FORBIDDEN, "cannot_write_to_library")
        ).collect { case (true, fail) => fail }.headOption

      case r: SlackIntegrationDeleteRequest =>
        val spaces = libToChannelRepo.getActiveByIds(r.libToSlack).map(_.space) ++ channelToLibRepo.getActiveByIds(r.slackToLib).map(_.space)
        val spacesUserCannotModify = spaces.filter {
          case UserSpace(uid) => r.requesterId != uid
          case OrganizationSpace(orgId) => orgMembershipRepo.getByOrgIdAndUserId(orgId, r.requesterId).isEmpty
        }
        if (spacesUserCannotModify.nonEmpty) Some(LibraryFail(FORBIDDEN, "permission_denied"))
        else None
    }
  }

  def turnOnLibraryPush(integrationId: Id[LibraryToSlackChannel], webhook: SlackIncomingWebhook, identity: SlackIdentifyResponse): Id[Library] = {
    db.readWrite { implicit session =>
      val integration = libToChannelRepo.get(integrationId)
      if (integration.isActive && integration.slackTeamId == identity.teamId && integration.slackChannelName == webhook.channelName) {
        val updatedIntegration = {
          if (integration.slackUserId == identity.userId) integration else integration.copy(slackUserId = identity.userId, slackChannelId = None) // resetting channelId with userId to be safe
        }.withStatus(SlackIntegrationStatus.On)
        libToChannelRepo.save(updatedIntegration)
      }
      integration.libraryId
    }
  }

  def turnOnChannelIngestion(integrationId: Id[SlackChannelToLibrary], identity: SlackIdentifyResponse): Id[Library] = {
    db.readWrite { implicit session =>
      val integration = channelToLibRepo.get(integrationId)
      if (integration.isActive && integration.slackTeamId == identity.teamId) {
        val updatedIntegration = {
          if (integration.slackUserId == identity.userId) integration else integration.copy(slackUserId = identity.userId, slackChannelId = None) // resetting channelId with userId to be safe
        }.withStatus(SlackIntegrationStatus.On)
        channelToLibRepo.save(updatedIntegration)
      }
      integration.libraryId
    }
  }

  def modifyIntegrations(request: SlackIntegrationModifyRequest): Try[SlackIntegrationModifyResponse] = db.readWrite { implicit s =>
    validateRequest(request) match {
      case Some(fail) => Failure(fail)
      case None =>
        Success(unsafeModifyIntegrations(request))
    }
  }
  private def unsafeModifyIntegrations(request: SlackIntegrationModifyRequest)(implicit session: RWSession): SlackIntegrationModifyResponse = {
    request.libToSlack.foreach {
      case (ltsId, mods) => libToChannelRepo.save(libToChannelRepo.get(ltsId).withModifications(mods))
    }
    request.slackToLib.foreach {
      case (stlId, mods) => channelToLibRepo.save(channelToLibRepo.get(stlId).withModifications(mods))
    }
    SlackIntegrationModifyResponse(request.libToSlack.size + request.slackToLib.size)
  }

  def deleteIntegrations(request: SlackIntegrationDeleteRequest): Try[SlackIntegrationDeleteResponse] = db.readWrite { implicit s =>
    validateRequest(request) match {
      case Some(fail) => Failure(fail)
      case None =>
        Success(unsafeDeleteIntegrations(request))
    }
  }
  private def unsafeDeleteIntegrations(request: SlackIntegrationDeleteRequest)(implicit session: RWSession): SlackIntegrationDeleteResponse = {
    request.libToSlack.foreach { ltsId => libToChannelRepo.deactivate(libToChannelRepo.get(ltsId)) }
    request.slackToLib.foreach { stlId => channelToLibRepo.deactivate(channelToLibRepo.get(stlId)) }
    SlackIntegrationDeleteResponse(request.libToSlack.size + request.slackToLib.size)
  }

  def fetchMissingChannelIds(): Future[Unit] = {
    log.info("Fetching missing Slack channel ids.")
    val (channelsWithMissingIds, tokensWithScopesByUserIdAndTeamId) = db.readOnlyMaster { implicit session =>
      val channelsWithMissingIds = libToChannelRepo.getWithMissingChannelId() ++ channelToLibRepo.getWithMissingChannelId() ++ slackIncomingWebhookInfoRepo.getWithMissingChannelId()
      val uniqueUserIdAndTeamIds = channelsWithMissingIds.map { case (userId, teamId, channelName) => (userId, teamId) }
      val tokensWithScopesByUserIdAndTeamId = uniqueUserIdAndTeamIds.map {
        case (userId, teamId) => (userId, teamId) ->
          slackTeamMembershipRepo.getBySlackTeamAndUser(teamId, userId).flatMap(m => m.token.map((_, m.scopes)))
      }.toMap
      (channelsWithMissingIds, tokensWithScopesByUserIdAndTeamId)
    }
    log.info(s"Fetching ${channelsWithMissingIds.size} missing channel ids.")
    FutureHelpers.sequentialExec(channelsWithMissingIds) {
      case (userId, teamId, channelName) =>
        log.info(s"Fetching channelId for Slack channel $channelName via user $userId in team $teamId")
        tokensWithScopesByUserIdAndTeamId(userId, teamId) match {
          case Some((token, scopes)) if scopes.contains(SlackAuthScope.SearchRead) => slackClient.getChannelId(token, channelName).map {
            case Some(channelId) =>
              log.info(s"Found channelId $channelId for Slack channel $channelName via user $userId in team $teamId")
              db.readWrite { implicit session =>
                libToChannelRepo.fillInMissingChannelId(userId, teamId, channelName, channelId)
                channelToLibRepo.fillInMissingChannelId(userId, teamId, channelName, channelId)
                slackIncomingWebhookInfoRepo.fillInMissingChannelId(userId, teamId, channelName, channelId)
              }
            case None => airbrake.notify(s"ChannelId not found Slack for channel $channelName via user $userId in team $teamId.")
          } recover {
            case error =>
              airbrake.notify(s"Unexpected error while fetching channelId for Slack channel $channelName via user $userId in team $teamId", error)
              ()
          }
          case Some((invalidToken, invalidScopes)) =>
            Future.successful(())
          case None =>
            Future.successful(())
        }
    }
  }

  def setupSlackTeam(userId: Id[User], identity: SlackIdentifyResponse, organizationId: Option[Id[Organization]])(implicit context: HeimdalContext): Future[SlackTeam] = {
    val (slackTeam, userHasNoOrg) = db.readWrite { implicit session =>
      (slackTeamRepo.internSlackTeam(identity), orgMembershipRepo.getAllByUserId(userId).isEmpty)
    }
    organizationId match {
      case Some(orgId) => Future.fromTry(connectSlackTeamToOrganization(userId, slackTeam.slackTeamId, orgId))
      case None if slackTeam.organizationId.isEmpty && userHasNoOrg => createOrganizationForSlackTeam(userId, slackTeam.slackTeamId)
      case _ => Future.successful(slackTeam)
    }
  }

  def createOrganizationForSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[SlackTeam] = {
    db.readOnlyMaster { implicit session =>
      val slackTeamOpt = slackTeamRepo.getBySlackTeamId(slackTeamId)
      val validTokenOpt = slackTeamMembershipRepo.getByUserId(userId).filter(_.slackTeamId == slackTeamId).flatMap(_.tokenWithScopes).collectFirst {
        case SlackTokenWithScopes(token, scopes) if scopes.contains(SlackAuthScope.TeamRead) => token
      }
      (slackTeamOpt, validTokenOpt)
    } match {
      case (Some(team), Some(token)) if team.organizationId.isEmpty =>
        slackClient.getTeamInfo(token).flatMap { teamInfo =>
          val orgInitialValues = OrganizationInitialValues(name = teamInfo.name.value, description = None, site = teamInfo.emailDomains.headOption.map(_.value))
          orgCommander.createOrganization(OrganizationCreateRequest(userId, orgInitialValues)) match {
            case Right(createdOrg) =>
              val orgId = createdOrg.newOrg.id.get
              val futureAvatar = teamInfo.icon.maxByOpt(_._1) match {
                case None => Future.successful(())
                case Some((_, imageUrl)) => orgAvatarCommander.persistRemoteOrganizationAvatars(orgId, imageUrl).imap(_ => ())
              }
              val connectedTeamMaybe = connectSlackTeamToOrganization(userId, slackTeamId, createdOrg.newOrg.id.get)
              futureAvatar.flatMap { _ =>
                Future.fromTry(connectedTeamMaybe).flatMap { _ =>
                  setupLatestSlackChannels(userId, slackTeamId).map { _ =>
                    db.readOnlyMaster { implicit session => slackTeamRepo.get(team.id.get) }
                  }
                }
              }
            case Left(error) => Future.failed(error)
          }
        }
      case (teamOpt, _) => Future.failed(UnauthorizedSlackTeamOrganizationModificationException(teamOpt, userId, None))
    }
  }

  private def canConnectSlackTeamToOrganization(team: SlackTeam, userId: Id[User], newOrganizationId: Id[Organization])(implicit session: RSession): Boolean = {
    val isSlackTeamMember = slackTeamMembershipRepo.getByUserId(userId).map(_.slackTeamId).contains(team.slackTeamId)
    lazy val hasOrgPermissions = permissionCommander.getOrganizationPermissions(newOrganizationId, Some(userId)).contains(SlackCommander.slackSetupPermission)
    isSlackTeamMember && hasOrgPermissions
  }

  def connectSlackTeamToOrganization(userId: Id[User], slackTeamId: SlackTeamId, newOrganizationId: Id[Organization]): Try[SlackTeam] = {
    db.readWrite { implicit session =>
      slackTeamRepo.getBySlackTeamId(slackTeamId) match {
        case Some(team) if !team.organizationId.contains(newOrganizationId) && canConnectSlackTeamToOrganization(team, userId, newOrganizationId) => Success(slackTeamRepo.save(team.copy(organizationId = Some(newOrganizationId), lastChannelCreatedAt = None)))
        case teamOpt => Failure(UnauthorizedSlackTeamOrganizationModificationException(teamOpt, userId, Some(newOrganizationId)))
      }
    }
  }

  def getOrganizationsToConnect(userId: Id[User]): Map[Id[Organization], OrganizationInfo] = {
    val validOrgIds = {
      val orgIds = orgMembershipCommander.getAllOrganizationsForUser(userId).toSet
      val permissions = db.readOnlyMaster { implicit session => permissionCommander.getOrganizationsPermissions(orgIds, Some(userId)) }
      orgIds.filter { orgId => permissions.get(orgId).exists(_.contains(SlackCommander.slackSetupPermission)) }
    }
    organizationInfoCommander.getOrganizationInfos(validOrgIds, Some(userId))
  }

  def setupSlackChannel(team: SlackTeam, membership: SlackTeamMembership, channel: SlackChannelInfo)(implicit context: HeimdalContext): Either[LibraryFail, Library] = {
    require(membership.slackTeamId == team.slackTeamId, s"SlackTeam ${team.id.get}/${team.slackTeamId.value} doesn't match SlackTeamMembership ${membership.id.get}/${membership.slackTeamId.value}")
    require(membership.userId.isDefined, s"SlackTeamMembership ${membership.id.get} doesn't belong to any user.")

    val userId = membership.userId.get
    val libraryName = channel.channelName.value
    val librarySpace = LibrarySpace(userId, team.organizationId)

    val initialValues = LibraryInitialValues(
      name = libraryName,
      visibility = librarySpace match {
        case UserSpace(_) => LibraryVisibility.SECRET
        case OrganizationSpace(_) => LibraryVisibility.ORGANIZATION
      },
      slug = LibrarySlug.generateFromName(libraryName),
      kind = Some(LibraryKind.SLACK_CHANNEL),
      description = channel.purpose.map(_.value) orElse channel.topic.map(_.value),
      space = Some(librarySpace)
    )
    libraryCommander.createLibrary(initialValues, userId) tap {
      case Left(_) =>
      case Right(library) =>
        db.readWrite { implicit session =>
          libToChannelRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
            requesterId = userId,
            space = librarySpace,
            libraryId = library.id.get,
            slackUserId = membership.slackUserId,
            slackTeamId = membership.slackTeamId,
            slackChannelId = Some(channel.channelId),
            slackChannelName = channel.channelName,
            status = SlackIntegrationStatus.On
          ))
          channelToLibRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
            requesterId = userId,
            space = librarySpace,
            libraryId = library.id.get,
            slackUserId = membership.slackUserId,
            slackTeamId = membership.slackTeamId,
            slackChannelId = Some(channel.channelId),
            slackChannelName = channel.channelName,
            status = SlackIntegrationStatus.On
          ))
        }
    }
  }

  def setupLatestSlackChannels(userId: Id[User], teamId: SlackTeamId)(implicit context: HeimdalContext): Future[Map[SlackChannel, Either[LibraryFail, Library]]] = {
    val (teamOpt, membershipOpt, integratedChannelIds) = db.readOnlyMaster { implicit session =>
      val teamOpt = slackTeamRepo.getBySlackTeamId(teamId)
      val membershipOpt = slackTeamMembershipRepo.getByUserId(userId).find(_.slackTeamId == teamId)
      val integratedChannelIds = teamOpt.flatMap(_.organizationId).map { orgId =>
        channelToLibRepo.getIntegrationsByOrg(orgId).filter(_.slackTeamId == teamId).flatMap(_.slackChannelId).toSet
      } getOrElse Set.empty
      (teamOpt, membershipOpt, integratedChannelIds)
    }
    (teamOpt, membershipOpt) match {
      case (Some(team), Some(membership)) if membership.token.isDefined && team.organizationId.isDefined =>
        slackClient.getChannels(membership.token.get, excludeArchived = true).map { channels =>
          def shouldBeIgnored(channel: SlackChannelInfo) = channel.isArchived || integratedChannelIds.contains(channel.channelId) || team.lastChannelCreatedAt.exists(channel.createdAt <= _)
          channels.sortBy(_.createdAt).collect {
            case channel if !shouldBeIgnored(channel) =>
              SlackChannel(channel.channelId, channel.channelName) -> setupSlackChannel(team, membership, channel)
          }.toMap tap { newLibraries =>
            if (newLibraries.values.forall(_.isRight)) {
              channels.map(_.createdAt).maxOpt.foreach { lastChannelCreatedAt =>
                db.readWrite { implicit sessio =>
                  slackTeamRepo.save(team.copy(lastChannelCreatedAt = Some(lastChannelCreatedAt)))
                }
              }
            }
          }
        }
      case _ => Future.failed(InvalidSlackSetupException(userId, teamOpt, membershipOpt))
    }
  }

  def pushDigestNotificationsForRipeTeams(): Future[Unit] = {
    inhouseSlackClient.sendToSlack(InhouseSlackChannel.TEST_RYAN, SlackMessageRequest.inhouse(DescriptionElements("Scheduled plugin: pushing digest notifs")))
    val ripeTeamsFut = db.readOnlyReplicaAsync { implicit s =>
      slackTeamRepo.getRipeForPushingDigestNotification(lastPushOlderThan = clock.now minus SlackCommander.minPeriodBetweenDigestNotifications) tap { teams =>
        inhouseSlackClient.sendToSlack(InhouseSlackChannel.TEST_RYAN, SlackMessageRequest.inhouse(DescriptionElements("The ripe teams are: " + teams.map(_.id.get))))
      }
    }
    for {
      ripeTeams <- ripeTeamsFut
      pushes <- FutureHelpers.accumulateRobustly(ripeTeams)(pushDigestNotificationForTeam)
    } yield Unit
  }

  private def createSlackDigest(slackTeam: SlackTeam)(implicit session: RSession): Option[SlackTeamDigest] = {
    inhouseSlackClient.sendToSlack(InhouseSlackChannel.TEST_RYAN, SlackMessageRequest.inhouse(DescriptionElements(s"Creating slack digest for team ${slackTeam.slackTeamId}")))
    for {
      org <- slackTeam.organizationId.flatMap(organizationInfoCommander.getBasicOrganizationHelper)
      numIngestedKeepsByLibrary = {
        val teamIntegrations = channelToLibRepo.getBySlackTeam(slackTeam.slackTeamId)
        val teamChannelIds = teamIntegrations.flatMap(_.slackChannelId).toSet
        val librariesIngestedInto = libRepo.getActiveByIds(teamIntegrations.map(_.libraryId).toSet)
        librariesIngestedInto.map {
          case (libId, lib) =>
            val newKeepIds = ktlRepo.getByLibraryAddedSince(libId, slackTeam.lastDigestNotificationAt).map(_.keepId).toSet
            val newSlackKeeps = keepRepo.getByIds(newKeepIds).values.filter(_.source == KeepSource.slack).map(_.id.get).toSet
            val numIngestedKeeps = attributionRepo.getByKeepIds(newSlackKeeps).values.collect {
              case SlackAttribution(msg) if teamChannelIds.contains(msg.channel.id) => 1
            }.sum
            lib -> numIngestedKeeps
        }
      }
      digest <- Some(SlackTeamDigest(slackTeam, org, numIngestedKeepsByLibrary)).filter(_.numIngestedKeeps >= 10)
    } yield digest
  }

  private def describeDigest(digest: SlackTeamDigest)(implicit session: RSession): SlackMessageRequest = {
    import DescriptionElements._
    val lines = List(
      List(DescriptionElements("We have captured", digest.numIngestedKeeps, "links from", digest.slackTeam.slackTeamName.value)),
      digest.numIngestedKeepsByLibrary.collect {
        case (lib, num) if num > 0 => DescriptionElements("    - ", num, "were saved in", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute))
      }.toList,
      List(DescriptionElements("Check them at at", digest.org, "'s page on Kifi, or search through them using the /kifi Slack command"))
    ).flatten

    SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(DescriptionElements.unlines(lines)))
  }
  private def pushDigestNotificationForTeam(team: SlackTeam): Future[Unit] = {
    inhouseSlackClient.sendToSlack(InhouseSlackChannel.TEST_RYAN, SlackMessageRequest.inhouse(DescriptionElements(s"Pushing digest notif for team ${team.id.get}")))
    val now = clock.now
    val msgOpt = db.readOnlyMaster { implicit s => createSlackDigest(team).map(describeDigest) }
    val generalChannelFut = team.generalChannelId match {
      case Some(channelId) => Future.successful(Some(channelId))
      case None => slackClient.getGeneralChannelId(team.slackTeamId)
    }
    generalChannelFut.onFailure {
      case f =>
        inhouseSlackClient.sendToSlack(InhouseSlackChannel.TEST_RYAN, SlackMessageRequest.inhouse(DescriptionElements(s"Failed to get the general channel for team ${team.slackTeamId} because ${f.getMessage}")))
    }
    generalChannelFut.flatMap { generalChannelOpt =>
      inhouseSlackClient.sendToSlack(InhouseSlackChannel.TEST_RYAN, SlackMessageRequest.inhouse(DescriptionElements(s"Team ${team.id.get} has general $generalChannelOpt. Trying to push a digest to it: ${msgOpt.map(_.text)}")))
      val pushOpt = for {
        msg <- msgOpt
        generalChannel <- generalChannelOpt
      } yield {
        slackClient.sendToSlackTeam(team.slackTeamId, generalChannel, msg).andThen {
          case Success(_: Unit) =>
            db.readWrite { implicit s =>
              slackTeamRepo.save(slackTeamRepo.get(team.id.get).withGeneralChannelId(generalChannel).withLastDigestNotificationAt(now))
            }
            inhouseSlackClient.sendToSlack(InhouseSlackChannel.TEST_RYAN, SlackMessageRequest.inhouse(DescriptionElements("Pushed a digest to", team.slackTeamName.value)))
          case Failure(fail) =>
            inhouseSlackClient.sendToSlack(InhouseSlackChannel.TEST_RYAN, SlackMessageRequest.inhouse(DescriptionElements("Failed to push a digest to", team.slackTeamName.value, "because", fail.getMessage)))
        }
      }
      pushOpt.getOrElse(Future.successful(Unit))
    }
  }
}

sealed abstract class SlackAuthenticatedAction[T](val action: String)(implicit val format: Format[T]) {
  def readsDataAndThen[R](f: (SlackAuthenticatedAction[T], T) => R): Reads[R] = format.map { data => f(this, data) }
}
object SlackAuthenticatedAction {
  case object SetupLibraryIntegrations extends SlackAuthenticatedAction[PublicId[Library]]("setup_library_integrations")
  case object TurnOnLibraryPush extends SlackAuthenticatedAction[PublicId[LibraryToSlackChannel]]("turn_on_library_push")
  case object TurnOnChannelIngestion extends SlackAuthenticatedAction[PublicId[SlackChannelToLibrary]]("turn_on_channel_ingestion")
  case object SetupSlackTeam extends SlackAuthenticatedAction[Option[PublicId[Organization]]]("setup_slack_team")

  val all: Set[SlackAuthenticatedAction[_]] = Set(SetupLibraryIntegrations, TurnOnLibraryPush, TurnOnChannelIngestion, SetupSlackTeam)

  case class UnknownSlackAuthenticatedActionException(action: String) extends Exception(s"Unknown SlackAuthenticatedAction: $action")
  def fromString(action: String): Try[SlackAuthenticatedAction[_]] = {
    all.collectFirst {
      case authAction if authAction.action equalsIgnoreCase action => Success(authAction)
    } getOrElse Failure(UnknownSlackAuthenticatedActionException(action))
  }

  private implicit val format: Format[SlackAuthenticatedAction[_]] = Format(
    Reads(_.validate[String].flatMap[SlackAuthenticatedAction[_]](action => SlackAuthenticatedAction.fromString(action).map(JsSuccess(_)).recover { case error => JsError(error.getMessage) }.get)),
    Writes(action => JsString(action.action))
  )

  implicit def writesWithData[T]: Writes[(SlackAuthenticatedAction[T], T)] = Writes { case (action, data) => Json.obj("action" -> action, "data" -> action.format.writes(data)) }
  implicit def toState[T](actionWithData: (SlackAuthenticatedAction[T], T)): SlackState = SlackState(Json.toJson(actionWithData))

  def readsWithDataJson: Reads[(SlackAuthenticatedAction[_], JsValue)] = Reads { value =>
    for {
      action <- (value \ "action").validate[SlackAuthenticatedAction[_]]
      dataJson <- (value \ "data").validate[JsValue]
    } yield (action, dataJson)
  }
}


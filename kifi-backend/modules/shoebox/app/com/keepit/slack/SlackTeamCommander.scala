package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.util.{ Ord, DescriptionElements, LinkElement }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.slack.models._
import org.joda.time.{ Period, Days, Interval }

import scala.concurrent.{ ExecutionContext, Future }
import scala.reflect.macros.whitebox
import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[SlackTeamCommanderImpl])
trait SlackTeamCommander {
  def setupSlackTeam(userId: Id[User], identity: SlackIdentifyResponse, organizationId: Option[Id[Organization]])(implicit context: HeimdalContext): Future[SlackTeam]
  def createOrganizationForSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[SlackTeam]
  def connectSlackTeamToOrganization(userId: Id[User], slackTeamId: SlackTeamId, organizationId: Id[Organization]): Try[SlackTeam]
  def getOrganizationsToConnect(userId: Id[User]): Map[Id[Organization], OrganizationInfo]
  def setupSlackChannel(team: SlackTeam, membership: SlackTeamMembership, channel: SlackChannelInfo)(implicit context: HeimdalContext): Either[LibraryFail, Library]
  def setupLatestSlackChannels(userId: Id[User], teamId: SlackTeamId)(implicit context: HeimdalContext): Future[Map[SlackChannel, Either[LibraryFail, Library]]]
  def pushDigestNotificationsForRipeTeams(): Future[Unit]
}

@Singleton
class SlackTeamCommanderImpl @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  channelToLibRepo: SlackChannelToLibraryRepo,
  libToChannelRepo: LibraryToSlackChannelRepo,
  slackClient: SlackClientWrapper,
  pathCommander: PathCommander,
  permissionCommander: PermissionCommander,
  orgCommander: OrganizationCommander,
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
    extends SlackTeamCommander with Logging {

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
                Future.fromTry(connectedTeamMaybe)
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
    val ripeTeamsFut = db.readOnlyReplicaAsync { implicit s =>
      slackTeamRepo.getRipeForPushingDigestNotification(lastPushOlderThan = clock.now minus SlackCommander.minPeriodBetweenDigestNotifications)
    }
    for {
      ripeTeams <- ripeTeamsFut
      pushes <- FutureHelpers.accumulateRobustly(ripeTeams)(pushDigestNotificationForTeam)
    } yield Unit
  }

  private def createSlackDigest(slackTeam: SlackTeam)(implicit session: RSession): Option[SlackTeamDigest] = {
    for {
      org <- slackTeam.organizationId.flatMap(organizationInfoCommander.getBasicOrganizationHelper)
      numIngestedKeepsByLibrary = {
        val teamIntegrations = channelToLibRepo.getBySlackTeam(slackTeam.slackTeamId)
        val teamChannelIds = teamIntegrations.flatMap(_.slackChannelId).toSet
        val librariesIngestedInto = libRepo.getActiveByIds(teamIntegrations.map(_.libraryId).toSet).filter {
          case (_, lib) =>
            Set[LibraryVisibility](LibraryVisibility.ORGANIZATION, LibraryVisibility.PUBLISHED).contains(lib.visibility)
        }
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
      digest <- Some(SlackTeamDigest(
        slackTeam = slackTeam,
        timeSinceLastDigest = new Period(slackTeam.lastDigestNotificationAt, clock.now),
        org = org,
        numIngestedKeepsByLibrary = numIngestedKeepsByLibrary
      )).filter(_.numIngestedKeeps >= 10)
    } yield digest
  }

  private def describeDigest(digest: SlackTeamDigest)(implicit session: RSession): SlackMessageRequest = {
    import DescriptionElements._
    val topLibraries = digest.numIngestedKeepsByLibrary.toList.sortBy { case (lib, numKeeps) => numKeeps }(Ord.descending).take(3).collect { case (lib, numKeeps) if numKeeps > 0 => lib }
    val lines = List(
      DescriptionElements("We have captured", digest.numIngestedKeeps, "links from", digest.slackTeam.slackTeamName.value, "in the last", digest.timeSinceLastDigest.getDays, "days"),
      DescriptionElements("Your most active", if (topLibraries.length > 1) "libraries are" else "library is",
        DescriptionElements.unwordsPretty(topLibraries.map(lib => DescriptionElements(lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute))))
      ),
      DescriptionElements(
        "Check them at at", digest.org, "'s page on Kifi,",
        "search through them using the /kifi Slack command,",
        "and see them in your Google searches when you install the", "browser extension" --> LinkElement(PathCommander.browserExtension)
      )
    )

    SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(DescriptionElements.unlines(lines)))
  }
  private def pushDigestNotificationForTeam(team: SlackTeam): Future[Unit] = {
    val now = clock.now
    val msgOpt = db.readOnlyMaster { implicit s => createSlackDigest(team).map(describeDigest) }
    val generalChannelFut = team.generalChannelId match {
      case Some(channelId) => Future.successful(Some(channelId))
      case None => slackClient.getGeneralChannelId(team.slackTeamId)
    }
    generalChannelFut.flatMap { generalChannelOpt =>
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

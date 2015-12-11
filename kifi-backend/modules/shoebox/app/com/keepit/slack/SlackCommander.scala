package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.{ PermissionCommander, PathCommander }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.performance.StatsdTiming
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.common.util.{ DescriptionElements, LinkElement }
import com.keepit.controllers.website.DeepLinkRouter
import com.keepit.model.ExternalLibrarySpace.{ ExternalOrganizationSpace, ExternalUserSpace }
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.social.BasicUser
import com.kifi.macros.json
import play.api.http.Status._
import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Failure, Success, Try }
import com.keepit.common.core._

@json
case class LibraryToSlackIntegrationInfo(
  id: PublicId[LibraryToSlackChannel],
  status: SlackIntegrationStatus,
  authLink: Option[String])

@json
case class SlackToLibraryIntegrationInfo(
  id: PublicId[SlackChannelToLibrary],
  status: SlackIntegrationStatus,
  authLink: Option[String])

@json
case class LibrarySlackIntegrationInfo(
  teamName: SlackTeamName,
  channelName: SlackChannelName,
  creatorName: SlackUsername,
  space: ExternalLibrarySpace,
  toSlack: Option[LibraryToSlackIntegrationInfo],
  fromSlack: Option[SlackToLibraryIntegrationInfo])

@json
case class LibrarySlackInfo(
  link: String,
  integrations: Seq[LibrarySlackIntegrationInfo])

@ImplementedBy(classOf[SlackCommanderImpl])
trait SlackCommander {
  // Open their own DB sessions, intended to be called directly from controllers
  def registerAuthorization(userId: Id[User], auth: SlackAuthorizationResponse, identity: SlackIdentifyResponse): Unit
  def setupIntegrations(userId: Id[User], libId: Id[Library], webhook: SlackIncomingWebhook, identity: SlackIdentifyResponse): Unit
  def modifyIntegrations(request: SlackIntegrationModifyRequest): Try[SlackIntegrationModifyResponse]
  def deleteIntegrations(request: SlackIntegrationDeleteRequest): Try[SlackIntegrationDeleteResponse]
  def fetchMissingChannelIds(): Future[Unit]

  // For use in the LibraryInfoCommander to send info down to clients
  def getSlackIntegrationsForLibraries(userId: Id[User], libraryIds: Set[Id[Library]]): Map[Id[Library], LibrarySlackInfo]
  def getIntegrationsBySlackChannel(teamId: SlackTeamId, channelId: SlackChannelId): SlackChannelIntegrations
}

@Singleton
class SlackCommanderImpl @Inject() (
  db: Database,
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
  libRepo: LibraryRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  inhouseSlackClient: InhouseSlackClient)
    extends SlackCommander with Logging {

  def registerAuthorization(userId: Id[User], auth: SlackAuthorizationResponse, identity: SlackIdentifyResponse): Unit = {
    require(auth.teamId == identity.teamId && auth.teamName == identity.teamName)
    db.readWrite { implicit s =>
      slackTeamMembershipRepo.internBySlackTeamAndUser(SlackTeamMembershipInternRequest(
        userId = userId,
        slackUserId = identity.userId,
        slackUsername = identity.userName,
        slackTeamId = auth.teamId,
        slackTeamName = auth.teamName,
        token = auth.accessToken,
        scopes = auth.scopes
      )).get
      auth.incomingWebhook.foreach { webhook =>
        slackIncomingWebhookInfoRepo.save(SlackIncomingWebhookInfo(
          slackUserId = identity.userId,
          slackTeamId = identity.teamId,
          slackChannelId = None,
          webhook = webhook,
          lastPostedAt = None
        ))
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
        slackChannelName = webhook.channelName
      ))
      channelToLibRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
        requesterId = userId,
        space = defaultSpace,
        libraryId = libId,
        slackUserId = identity.userId,
        slackTeamId = identity.teamId,
        slackChannelId = None,
        slackChannelName = webhook.channelName
      ))
    }
    val welcomeMsg = db.readOnlyMaster { implicit s =>
      import DescriptionElements._
      val lib = libRepo.get(libId)
      DescriptionElements(
        "A new Kifi integration was just set up.",
        "Keeps from", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute), "will be posted to this channel."
      )
    }
    slackClient.sendToSlack(webhook, SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(welcomeMsg)).quiet) andThen {
      case Success(()) =>
        libToSlackPusher.pushUpdatesToSlack(libId) andThen {
          case Success(_) =>
            fetchMissingChannelIds()
        }
    }

    SafeFuture {
      val inhouseMsg = db.readOnlyReplica { implicit s =>
        import DescriptionElements._
        val lib = libRepo.get(libId)
        val user = basicUserRepo.load(userId)
        DescriptionElements(user, "set up Slack integrations for", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute))
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
            airbrake.notify(s"Missing search scope for token $invalidToken while fetching channelId for Slack channel $channelName via user $userId in team $teamId")
            Future.successful(())
          case None =>
            airbrake.notify(s"Missing token while fetching channelId for channel $channelName via user $userId in team $teamId")
            Future.successful(())
        }
    }
  }

  @StatsdTiming("SlackCommander.getSlackIntegrationsForLibraries")
  def getSlackIntegrationsForLibraries(viewerId: Id[User], libraryIds: Set[Id[Library]]): Map[Id[Library], LibrarySlackInfo] = {
    db.readOnlyMaster { implicit session =>
      val userOrgs = orgMembershipRepo.getAllByUserId(viewerId).map(_.organizationId).toSet
      val slackToLibs = channelToLibRepo.getUserVisibleIntegrationsForLibraries(viewerId, userOrgs, libraryIds)
      val libToSlacks = libToChannelRepo.getUserVisibleIntegrationsForLibraries(viewerId, userOrgs, libraryIds)

      val teamMembershipMap = {
        val slackUserTeamPairs = slackToLibs.map(stl => (stl.slackUserId, stl.slackTeamId)).toSet ++ libToSlacks.map(lts => (lts.slackUserId, lts.slackTeamId)).toSet
        slackUserTeamPairs.flatMap {
          case k @ (slackUserId, slackTeamId) => slackTeamMembershipRepo.getBySlackTeamAndUser(slackTeamId, slackUserId).map { v => k -> v }
        }.toMap
      }
      val teamNameByTeamId = teamMembershipMap.map { // intentionally drops duplicates; hopefully they are all the same team name
        case ((_, slackTeamId), stm) => slackTeamId -> stm.slackTeamName
      }
      val externalSpaceBySpace: Map[LibrarySpace, ExternalLibrarySpace] = {
        (slackToLibs.map(_.space) ++ libToSlacks.map(_.space)).map {
          case space @ OrganizationSpace(orgId) => space -> ExternalOrganizationSpace(Organization.publicId(orgId))
          case space @ UserSpace(userId) => space -> ExternalUserSpace(basicUserRepo.load(userId).externalId)
        }.toMap
      }

      case class SlackIntegrationInfoKey(space: LibrarySpace, slackUserId: SlackUserId, slackTeamId: SlackTeamId, slackUsername: SlackUsername, slackChannelName: SlackChannelName)
      object SlackIntegrationInfoKey {
        def fromSTL(stl: SlackChannelToLibrary) = {
          val username = teamMembershipMap(stl.slackUserId, stl.slackTeamId).slackUsername
          SlackIntegrationInfoKey(stl.space, stl.slackUserId, stl.slackTeamId, username, stl.slackChannelName)
        }
        def fromLTS(lts: LibraryToSlackChannel) = {
          val username = teamMembershipMap(lts.slackUserId, lts.slackTeamId).slackUsername
          SlackIntegrationInfoKey(lts.space, lts.slackUserId, lts.slackTeamId, username, lts.slackChannelName)
        }
      }
      libraryIds.map { libId =>
        val fromSlacksThisLib = slackToLibs.filter(_.libraryId == libId)
        val toSlacksThisLib = libToSlacks.filter(_.libraryId == libId)

        def getAuthLink[I <: SlackIntegration](integrationId: PublicId[I], integration: I, requiredScopes: Set[SlackAuthScope]): Option[String] = {
          val existingScopes = teamMembershipMap.get(integration.slackUserId, integration.slackTeamId).toSet[SlackTeamMembership].flatMap(_.scopes)
          if (requiredScopes subsetOf existingScopes) None
          else Some(SlackAPI.OAuthAuthorize(requiredScopes, integrationId, Some(integration.slackTeamId)).url)
        }

        val fromSlackGroupedInfos = fromSlacksThisLib.groupBy(SlackIntegrationInfoKey.fromSTL).map {
          case (key, Seq(fs)) =>
            val fsId = SlackChannelToLibrary.publicId(fs.id.get)
            key -> SlackToLibraryIntegrationInfo(fsId, fs.status, getAuthLink(fsId, fs, SlackAuthScope.ingest))
        }
        val toSlackGroupedInfos = toSlacksThisLib.groupBy(SlackIntegrationInfoKey.fromLTS).map {
          case (key, Seq(ts)) =>
            val tsId = LibraryToSlackChannel.publicId(ts.id.get)
            key -> LibraryToSlackIntegrationInfo(tsId, ts.status, getAuthLink(tsId, ts, SlackAuthScope.push))
        }
        val integrations = (fromSlackGroupedInfos.keySet ++ toSlackGroupedInfos.keySet).map { key =>
          LibrarySlackIntegrationInfo(
            teamName = teamNameByTeamId(key.slackTeamId),
            channelName = key.slackChannelName,
            creatorName = key.slackUsername,
            space = externalSpaceBySpace(key.space),
            toSlack = toSlackGroupedInfos.get(key),
            fromSlack = fromSlackGroupedInfos.get(key)
          )
        }.toSeq.sortBy(x => (x.teamName.value, x.channelName.value, x.creatorName.value))
        libId -> LibrarySlackInfo(
          link = SlackAPI.OAuthAuthorize(SlackAuthScope.push ++ SlackAuthScope.ingest, Library.publicId(libId), None).url,
          integrations = integrations
        )

      }.toMap
    }
  }

  def getIntegrationsBySlackChannel(teamId: SlackTeamId, channelId: SlackChannelId): SlackChannelIntegrations = {
    db.readOnlyMaster { implicit session =>
      val channelToLibIntegrations = channelToLibRepo.getBySlackTeamAndChannel(teamId, channelId)
      val libToChannelIntegrations = libToChannelRepo.getBySlackTeamAndChannel(teamId, channelId)
      val integrationSpaces = (channelToLibIntegrations.map(stl => stl.libraryId -> stl.space) ++ libToChannelIntegrations.map(lts => lts.libraryId -> lts.space)).groupBy(_._1).mapValues(_.map(_._2).toSet)
      SlackChannelIntegrations(
        teamId = teamId,
        channelId = channelId,
        allLibraries = channelToLibIntegrations.map(_.libraryId).toSet ++ libToChannelIntegrations.map(_.libraryId),
        toLibraries = channelToLibIntegrations.collect { case integration if integration.status == SlackIntegrationStatus.On => integration.libraryId }.toSet,
        fromLibraries = libToChannelIntegrations.collect { case integration if integration.status == SlackIntegrationStatus.On => integration.libraryId }.toSet,
        spaces = integrationSpaces
      )
    }
  } tap { _ =>
    SafeFuture {
      ingestionCommander.ingestFromChannelPlease(teamId, channelId)
    }
  }
}

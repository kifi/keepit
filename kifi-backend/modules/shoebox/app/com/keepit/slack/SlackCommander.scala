package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.{ PermissionCommander, PathCommander }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
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

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success, Try }

@json
case class LibraryToSlackIntegrationInfo(
  id: PublicId[LibraryToSlackChannel],
  status: SlackIntegrationStatus)

@json
case class SlackToLibraryIntegrationInfo(
  id: PublicId[SlackChannelToLibrary],
  status: SlackIntegrationStatus)

@json
case class LibrarySlackIntegrationInfo(
  creator: BasicUser,
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

  // For use in the LibraryInfoCommander to send info down to clients
  def getSlackIntegrationsForLibraries(userId: Id[User], libraryIds: Set[Id[Library]]): Map[Id[Library], LibrarySlackInfo]
}

@Singleton
class SlackCommanderImpl @Inject() (
  db: Database,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackIncomingWebhookInfoRepo: SlackIncomingWebhookInfoRepo,
  channelToLibRepo: SlackChannelToLibraryRepo,
  libToChannelRepo: LibraryToSlackChannelRepo,
  slackClient: SlackClient,
  libToSlackPusher: LibraryToSlackChannelPusher,
  basicUserRepo: BasicUserRepo,
  pathCommander: PathCommander,
  permissionCommander: PermissionCommander,
  libRepo: LibraryRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration)
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
      ))
      auth.incomingWebhook.foreach { webhook =>
        slackIncomingWebhookInfoRepo.save(SlackIncomingWebhookInfo(
          ownerId = userId,
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
        userId = userId,
        space = defaultSpace,
        libraryId = libId,
        slackUserId = identity.userId,
        slackTeamId = identity.teamId,
        slackChannelId = None,
        slackChannelName = webhook.channelName
      ))
      channelToLibRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
        userId = userId,
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
    slackClient.sendToSlack(webhook.url, SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(welcomeMsg)).quiet)
      .andThen { case Failure(f: SlackAPIFailure) => db.readWrite { implicit s => markAsBroken(webhook, f) } }
      .andThen { case Success(()) => libToSlackPusher.pushToLibrary(libId) }
  }

  def registerSuccessfulPush(webhook: SlackIncomingWebhook)(implicit session: RWSession): Unit = {
    val now = clock.now
    slackIncomingWebhookInfoRepo.getByWebhook(webhook).foreach { whi =>
      if (whi.lastFailedAt.isDefined) {
        log.info(s"[SLACK-WEBHOOK] The webhook at ${webhook.url} recovered at $now")
      }
      slackIncomingWebhookInfoRepo.save(whi.withCleanSlate.withLastPostedAt(now))
    }
  }
  def markAsBroken(webhook: SlackIncomingWebhook, failure: SlackAPIFailure)(implicit session: RWSession): Unit = {
    val now = clock.now
    slackIncomingWebhookInfoRepo.getByWebhook(webhook).foreach { whi =>
      log.info(s"[SLACK-WEBHOOK] The webhook at ${webhook.url} recovered at $now")
      slackIncomingWebhookInfoRepo.save(whi.withLastFailure(failure).withLastFailedAt(now))
    }
  }
  private def validateRequest(request: SlackIntegrationRequest)(implicit session: RSession): Option[LibraryFail] = {
    request match {
      case r: SlackIntegrationCreateRequest =>
        val userHasAccessToSpace = r.space match {
          case UserSpace(uid) => r.userId == uid
          case OrganizationSpace(orgId) => orgMembershipRepo.getByOrgIdAndUserId(orgId, r.userId).isDefined
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

  def getSlackIntegrationsForLibraries(viewerId: Id[User], libraryIds: Set[Id[Library]]): Map[Id[Library], LibrarySlackInfo] = {
    db.readOnlyMaster { implicit session =>
      val userOrgs = orgMembershipRepo.getAllByUserId(viewerId).map(_.organizationId).toSet
      val slackToLibs = channelToLibRepo.getUserVisibleIntegrationsForLibraries(viewerId, userOrgs, libraryIds)
      val libToSlacks = libToChannelRepo.getUserVisibleIntegrationsForLibraries(viewerId, userOrgs, libraryIds)

      val teamNameByTeamId = (slackToLibs.map(_.slackTeamId) ++ libToSlacks.map(_.slackTeamId)).map { teamId =>
        val memberships = slackTeamMembershipRepo.getBySlackTeam(teamId)
        val teamName = memberships.head.slackTeamName
        assert(memberships.forall(_.slackTeamName == teamName)) // oh sweet jesus I hope so
        teamId -> teamName
      }.toMap
      val userNameByUserId = {
        val slackUserIds = slackToLibs.map(_.slackUserId) ++ libToSlacks.map(_.slackUserId)
        slackTeamMembershipRepo.getBySlackUserIds(slackUserIds.toSet).map {
          case (slackUserId, stm) => slackUserId -> stm.slackUsername
        }
      }
      val externalSpaceBySpace: Map[LibrarySpace, ExternalLibrarySpace] = {
        (slackToLibs.map(_.space) ++ libToSlacks.map(_.space)).map {
          case space @ OrganizationSpace(orgId) => space -> ExternalOrganizationSpace(Organization.publicId(orgId))
          case space @ UserSpace(userId) => space -> ExternalUserSpace(basicUserRepo.load(userId).externalId)
        }.toMap
      }

      libraryIds.map { libId =>
        val fromSlacksThisLib = slackToLibs.filter(_.libraryId == libId)
        val toSlacksThisLib = libToSlacks.filter(_.libraryId == libId)

        val fromSlacksGrouped = fromSlacksThisLib.groupBy(x => (x.ownerId, x.space, x.slackUserId, x.slackTeamId, x.slackChannelName)).map {
          case (key, Seq(fs)) => key -> SlackToLibraryIntegrationInfo(SlackChannelToLibrary.publicId(fs.id.get), fs.status)
        }
        val toSlacksGrouped = toSlacksThisLib.groupBy(x => (x.ownerId, x.space, x.slackUserId, x.slackTeamId, x.slackChannelName)).map {
          case (key, Seq(ts)) => key -> LibraryToSlackIntegrationInfo(LibraryToSlackChannel.publicId(ts.id.get), ts.status)
        }
        val integrations = (fromSlacksGrouped.keySet ++ toSlacksGrouped.keySet).map {
          case key @ (ownerId, space, slackUserId, slackTeamId, slackChannelName) =>
            LibrarySlackIntegrationInfo(
              creator = basicUserRepo.load(ownerId),
              teamName = teamNameByTeamId(slackTeamId),
              channelName = slackChannelName,
              creatorName = userNameByUserId(slackUserId),
              space = externalSpaceBySpace(space),
              toSlack = toSlacksGrouped.get(key),
              fromSlack = fromSlacksGrouped.get(key)
            )
        }.toSeq.sortBy(x => (x.teamName.value, x.channelName.value, x.creatorName.value))
        libId -> LibrarySlackInfo(
          link = SlackAPI.OAuthAuthorize(SlackAuthScope.library, DeepLinkRouter.libraryLink(Library.publicId(libId))).url,
          integrations = integrations
        )

      }.toMap
    }
  }

}

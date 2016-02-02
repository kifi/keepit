package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.core.{ anyExtensionOps, _ }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.SlackLog
import com.keepit.common.time.{ Clock, _ }
import com.keepit.common.util.DescriptionElements
import com.keepit.controllers.website.SlackController
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.slack.models._
import play.api.mvc.RequestHeader

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

sealed trait SlackResponse
object SlackResponse {
  // Client should blindly go to `url`, typically because they need to go to 3rd party to auth
  final case class RedirectClient(url: String) extends SlackResponse
  // Requested action performed. Url included is a suggestion about a good place to go next, but clients can be smarter.
  final case class ActionPerformed(url: Option[String] = None) extends SlackResponse
}

@ImplementedBy(classOf[SlackAuthenticationCommanderImpl])
trait SlackAuthenticationCommander {
  // put stuff here
  def processAuthorizedAction(userId: Id[User], slackTeamId: SlackTeamId, slackUserId: SlackUserId, action: SlackAuthenticatedAction, incomingWebhook: Option[SlackIncomingWebhook])(implicit context: HeimdalContext): Future[SlackResponse]
  def processActionOrElseAuthenticate(userId: Id[User], slackTeamIdOpt: Option[SlackTeamId], action: SlackAuthenticatedAction)(implicit context: HeimdalContext): Future[SlackResponse]
}

@Singleton
class SlackAuthenticationCommanderImpl @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  channelToLibRepo: SlackChannelToLibraryRepo,
  libToChannelRepo: LibraryToSlackChannelRepo,
  channelRepo: SlackChannelRepo,
  slackClient: SlackClientWrapper,
  slackOnboarder: SlackOnboarder,
  userValueRepo: UserValueRepo,
  slackCommander: SlackCommander,
  slackIntegrationCommander: SlackIntegrationCommander,
  slackTeamCommander: SlackTeamCommander,
  slackStateCommander: SlackAuthStateCommander,
  pathCommander: PathCommander,
  permissionCommander: PermissionCommander,
  orgCommander: OrganizationCommander,
  orgAvatarCommander: OrganizationAvatarCommander,
  libraryRepo: LibraryRepo,
  libraryCommander: LibraryCommander,
  orgMembershipRepo: OrganizationMembershipRepo,
  orgMembershipCommander: OrganizationMembershipCommander,
  organizationInfoCommander: OrganizationInfoCommander,
  clock: Clock,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends SlackAuthenticationCommander {
  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SLACK)

  private def redirectToLibrary(libraryId: Id[Library], showSlackDialog: Boolean): SlackResponse.RedirectClient = {
    val libraryUrl = db.readOnlyMaster { implicit s => pathCommander.libraryPageById(libraryId) }.absolute
    val redirectUrl = if (showSlackDialog) libraryUrl + "?showSlackDialog" else libraryUrl
    SlackResponse.RedirectClient(redirectUrl)
  }

  private def getOrgUrl(organizationId: Id[Organization]): String = {
    db.readOnlyMaster { implicit s => pathCommander.orgPageById(organizationId) }.absolute
  }

  private def redirectToOrganization(organizationId: Id[Organization], showSlackDialog: Boolean): SlackResponse.ActionPerformed = {
    val organizationUrl = getOrgUrl(organizationId)
    val redirectUrl = if (showSlackDialog) organizationUrl + "?showSlackDialog" else organizationUrl
    SlackResponse.ActionPerformed(Some(redirectUrl))
  }

  private def redirectToOrganizationIntegrations(organizationId: Id[Organization]): SlackResponse.ActionPerformed = {
    val redirectUrl = getOrgUrl(organizationId) + "/settings/integrations"
    SlackResponse.ActionPerformed(Some(redirectUrl))
  }

  private def hasSeenInstall(userId: Id[User]): Boolean = db.readOnlyMaster { implicit session => userValueRepo.getValue(userId, UserValues.hasSeenInstall) }
  private def redirectToInstall = {
    val redirectUrl = com.keepit.controllers.website.HomeControllerRoutes.install()
    SlackResponse.ActionPerformed(Some(redirectUrl))
  }

  def processAuthorizedAction(userId: Id[User], slackTeamId: SlackTeamId, slackUserId: SlackUserId, action: SlackAuthenticatedAction, incomingWebhook: Option[SlackIncomingWebhook])(implicit context: HeimdalContext): Future[SlackResponse] = {
    action match {
      case SetupLibraryIntegrations(libId) => incomingWebhook match {
        case Some(webhook) =>
          slackIntegrationCommander.setupIntegrations(userId, libId, webhook, slackTeamId, slackUserId)
          Future.successful(redirectToLibrary(libId, showSlackDialog = true))
        case _ => Future.failed(SlackActionFail.MissingWebhook(userId))
      }

      case TurnOnLibraryPush(integrationId) => incomingWebhook match {
        case Some(webhook) =>
          val libraryId = slackIntegrationCommander.turnOnLibraryPush(Id(integrationId), webhook, slackTeamId, slackUserId)
          Future.successful(redirectToLibrary(libraryId, showSlackDialog = true))
        case _ => Future.failed(SlackActionFail.MissingWebhook(userId))
      }

      case TurnOnChannelIngestion(integrationId) =>
        val libraryId = slackIntegrationCommander.turnOnChannelIngestion(Id(integrationId), slackTeamId, slackUserId)
        Future.successful(redirectToLibrary(libraryId, showSlackDialog = true))

      case AddSlackTeam() => slackTeamCommander.addSlackTeam(userId, slackTeamId).map {
        case (slackTeam, isNewOrg) =>
          slackTeam.organizationId match {
            case None => SlackResponse.RedirectClient(s"/integrations/slack/teams?slackTeamId=${slackTeam.slackTeamId.value}")
            case Some(orgId) =>
              if (isNewOrg) redirectToOrganization(orgId, showSlackDialog = true)
              else if (!hasSeenInstall(userId)) redirectToInstall
              else if (slackTeam.publicChannelsLastSyncedAt.isDefined) redirectToOrganization(orgId, showSlackDialog = false)
              else redirectToOrganizationIntegrations(orgId)
          }
      }

      case ConnectSlackTeam(orgId) => Future.fromTry {
        slackTeamCommander.connectSlackTeamToOrganization(userId, slackTeamId, orgId).map { _ =>
          redirectToOrganizationIntegrations(orgId)
        }
      }

      case CreateSlackTeam() => slackTeamCommander.createOrganizationForSlackTeam(userId, slackTeamId).map { slackTeam =>
        redirectToOrganization(slackTeam.organizationId.get, showSlackDialog = true)
      }

      case SyncPublicChannels(orgId) =>
        Future.fromTry(slackTeamCommander.connectSlackTeamToOrganization(userId, slackTeamId, orgId)).map { slackTeam =>
          slackTeamCommander.syncPublicChannels(userId, slackTeam)
          SlackResponse.ActionPerformed(redirectToOrganizationIntegrations(orgId).url.map(_ + s"/slack-confirm?slackTeamId=${slackTeamId.value}"))
        }
      case _ => throw new IllegalStateException(s"Action not handled by SlackController: $action")
    }
  }

  def processActionOrElseAuthenticate(userId: Id[User], slackTeamIdOpt: Option[SlackTeamId], action: SlackAuthenticatedAction)(implicit context: HeimdalContext): Future[SlackResponse] = {
    slackCommander.getIdentityAndMissingScopes(userId, slackTeamIdOpt, action).flatMap {
      case (Some((slackTeamId, slackUserId)), missingScopes) if missingScopes.isEmpty =>
        processAuthorizedAction(userId, slackTeamId, slackUserId, action, None)
      case (_, missingScopes) =>
        val authUrl = slackStateCommander.getAuthLink(action, slackTeamIdOpt, missingScopes, SlackController.REDIRECT_URI).url
        Future.successful(SlackResponse.RedirectClient(authUrl))
    }
  }

}

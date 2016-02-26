package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.controllers.website.SlackOAuthController
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }

sealed trait SlackResponse
object SlackResponse {
  // Client should blindly go to `url`, typically because they need to go to 3rd party to auth
  final case class RedirectClient(url: String) extends SlackResponse
  // Requested action performed. Url included is a suggestion about a good place to go next, but clients can be smarter.
  final case class ActionPerformed(url: Option[String] = None) extends SlackResponse
}

@ImplementedBy(classOf[SlackAuthenticationCommanderImpl])
trait SlackAuthenticationCommander {
  def getAuthLink(action: SlackAuthenticatedAction, teamId: Option[SlackTeamId], scopes: Set[SlackAuthScope], redirectUri: String): SlackAPI.Route
  def getSlackAction(state: SlackAuthState): Option[SlackAuthenticatedAction]
  def processAuthorizedAction(userId: Id[User], slackTeamId: SlackTeamId, slackUserId: SlackUserId, action: SlackAuthenticatedAction, incomingWebhook: Option[Id[SlackIncomingWebhookInfo]])(implicit context: HeimdalContext): Future[SlackResponse]
  def processActionOrElseAuthenticate(userId: Id[User], slackTeamIdOpt: Option[SlackTeamId], action: SlackAuthenticatedAction)(implicit context: HeimdalContext): Future[SlackResponse]
  def getIdentityAndMissingScopes(userIdOpt: Option[Id[User]], slackTeamIdOpt: Option[SlackTeamId], action: SlackAuthenticatedAction): Future[(Option[(SlackTeamId, SlackUserId)], Set[SlackAuthScope])]
}

@Singleton
class SlackAuthenticationCommanderImpl @Inject() (
  db: Database,
  userValueRepo: UserValueRepo,
  slackIdentityCommander: SlackIdentityCommander,
  slackIntegrationCommander: SlackIntegrationCommander,
  slackTeamCommander: SlackTeamCommander,
  pathCommander: PathCommander,
  implicit val executionContext: ExecutionContext,
  stateCache: SlackAuthStateCache,
  airbrake: AirbrakeNotifier)
    extends SlackAuthenticationCommander {

  private def setNewSlackState(action: SlackAuthenticatedAction): SlackAuthState = {
    SlackAuthState() tap { state => stateCache.direct.set(SlackAuthStateKey(state), action) }
  }

  def getAuthLink(action: SlackAuthenticatedAction, teamId: Option[SlackTeamId], scopes: Set[SlackAuthScope], redirectUri: String): SlackAPI.Route = {
    val state = setNewSlackState(action)
    SlackAPI.OAuthAuthorize(scopes, state, teamId, redirectUri)
  }

  def getSlackAction(state: SlackAuthState): Option[SlackAuthenticatedAction] = {
    stateCache.direct.get(SlackAuthStateKey(state))
  }

  private def redirectToLibrary(libraryId: Id[Library], showSlackDialog: Boolean): SlackResponse.ActionPerformed = {
    val libraryUrl = db.readOnlyMaster { implicit s => pathCommander.libraryPageById(libraryId) }.absolute
    val redirectUrl = if (showSlackDialog) libraryUrl + "?showSlackDialog" else libraryUrl
    SlackResponse.ActionPerformed(Some(redirectUrl))
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

  def processAuthorizedAction(userId: Id[User], slackTeamId: SlackTeamId, slackUserId: SlackUserId, action: SlackAuthenticatedAction, incomingWebhookId: Option[Id[SlackIncomingWebhookInfo]])(implicit context: HeimdalContext): Future[SlackResponse] = {
    def continueWith(nextAction: SlackAuthenticatedAction): Future[SlackResponse] = processAuthorizedAction(userId, slackTeamId, slackUserId, nextAction, incomingWebhookId)

    action match {
      case SetupLibraryIntegrations(libId, cachedWebhookId) => (incomingWebhookId orElse cachedWebhookId.map(Id[SlackIncomingWebhookInfo](_))) match {
        case Some(webhookId) =>
          Future.fromTry(slackIntegrationCommander.setupIntegrations(userId, libId, webhookId)).imap { team =>
            redirectToOrganizationIntegrations(team.organizationId.get)
          }

        case _ => Future.failed(SlackActionFail.MissingWebhook(userId))
      }

      case TurnLibraryPush(integrationId, _, turnOn) =>
        val libraryId = slackIntegrationCommander.turnLibraryPush(Id(integrationId), slackTeamId, slackUserId, turnOn)
        Future.successful(redirectToLibrary(libraryId, showSlackDialog = true))

      case TurnChannelIngestion(integrationId, turnOn) =>
        val libraryId = slackIntegrationCommander.turnChannelIngestion(Id(integrationId), slackTeamId, slackUserId, turnOn)
        Future.successful(redirectToLibrary(libraryId, showSlackDialog = true))

      case AddSlackTeam(andThen) => slackTeamCommander.addSlackTeam(userId, slackTeamId).flatMap {
        case (slackTeam, isNewOrg) =>
          slackTeam.organizationId match {
            case None => {
              val slackState = andThen.map {
                case SetupLibraryIntegrations(libraryId, _) if incomingWebhookId.isDefined => SetupLibraryIntegrations(libraryId, incomingWebhookId.map(_.id))
                case nextAction => nextAction
              }.map(setNewSlackState)
              val slackStateParam = slackState.map(state => s"&slackState=${state.state}") getOrElse ""
              val redirectUrl = s"/integrations/slack/teams?slackTeamId=${slackTeam.slackTeamId.value}$slackStateParam"
              Future.successful(SlackResponse.RedirectClient(redirectUrl))
            }
            case Some(orgId) =>
              andThen match {
                case Some(action) => continueWith(action)
                case None => Future.successful {
                  if (isNewOrg) redirectToOrganization(orgId, showSlackDialog = true)
                  else if (!hasSeenInstall(userId)) redirectToInstall
                  else if (slackTeam.publicChannelsLastSyncedAt.isDefined) redirectToOrganization(orgId, showSlackDialog = false)
                  else redirectToOrganizationIntegrations(orgId)
                }
              }
          }
      }

      case ConnectSlackTeam(orgId, andThen) => Future.fromTry(slackTeamCommander.connectSlackTeamToOrganization(userId, slackTeamId, orgId)).flatMap { _ =>
        andThen match {
          case Some(action) => continueWith(action)
          case None => Future.successful(redirectToOrganizationIntegrations(orgId))
        }
      }

      case CreateSlackTeam(andThen) => slackTeamCommander.createOrganizationForSlackTeam(userId, slackTeamId).flatMap { slackTeam =>
        andThen match {
          case Some(action) => continueWith(action)
          case None => Future.successful(redirectToOrganization(slackTeam.organizationId.get, showSlackDialog = true))
        }
      }

      case SyncPublicChannels() => slackTeamCommander.syncPublicChannels(userId, slackTeamId).map {
        case (orgId, _, _) =>
          SlackResponse.ActionPerformed(redirectToOrganizationIntegrations(orgId).url.map(_ + s"/slack-confirm?slackTeamId=${slackTeamId.value}"))
      }

      case _ => throw new IllegalStateException(s"Action not handled by SlackController: $action")
    }
  } tap (_.onFailure { case error => airbrake.notify(error) })

  def processActionOrElseAuthenticate(userId: Id[User], slackTeamIdOpt: Option[SlackTeamId], action: SlackAuthenticatedAction)(implicit context: HeimdalContext): Future[SlackResponse] = {
    getIdentityAndMissingScopes(Some(userId), slackTeamIdOpt, action).flatMap {
      case (Some((slackTeamId, slackUserId)), missingScopes) if missingScopes.isEmpty =>
        processAuthorizedAction(userId, slackTeamId, slackUserId, action, None)
      case (_, missingScopes) =>
        val authUrl = getAuthLink(action, slackTeamIdOpt, missingScopes, SlackOAuthController.REDIRECT_URI).url
        Future.successful(SlackResponse.RedirectClient(authUrl))
    }
  }

  def getIdentityAndMissingScopes(userIdOpt: Option[Id[User]], slackTeamIdOpt: Option[SlackTeamId], action: SlackAuthenticatedAction): Future[(Option[(SlackTeamId, SlackUserId)], Set[SlackAuthScope])] = {
    slackIdentityCommander.getIdentityAndExistingScopes(userIdOpt, slackTeamIdOpt).imap {
      case (identityOpt, existingScopes) =>
        (identityOpt, action.getMissingScopes(existingScopes))
    }
  }
}

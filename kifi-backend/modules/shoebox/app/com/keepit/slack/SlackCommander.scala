package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.slack.models._
import org.joda.time.Period
import play.api.libs.json._

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success, Try }

object SlackCommander {
  val slackSetupPermission = OrganizationPermission.EDIT_ORGANIZATION
  val minPeriodBetweenDigestNotifications = Period.minutes(1) // TODO(ryan): make this way slower
}

@ImplementedBy(classOf[SlackCommanderImpl])
trait SlackCommander {
  def registerAuthorization(userId: Id[User], auth: SlackAuthorizationResponse, identity: SlackIdentifyResponse): Unit
}

@Singleton
class SlackCommanderImpl @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackIncomingWebhookInfoRepo: SlackIncomingWebhookInfoRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  orgMembershipCommander: OrganizationMembershipCommander,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration)
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


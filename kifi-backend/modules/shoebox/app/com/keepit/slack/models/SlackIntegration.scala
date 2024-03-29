package com.keepit.slack.models

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DataBaseComponent
import com.keepit.common.json.{ EitherFormat, EnumFormat }
import com.keepit.common.reflection.Enumerator
import com.keepit.model._
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

abstract class SlackIntegrationStatus(val status: String)
object SlackIntegrationStatus extends Enumerator[SlackIntegrationStatus] {
  case object On extends SlackIntegrationStatus("on")
  case object Off extends SlackIntegrationStatus("off")
  case object Broken extends SlackIntegrationStatus("broken")
  def all = _all
  def get(str: String) = all.find(_.status == str)

  implicit val format: Format[SlackIntegrationStatus] = Format(
    EnumFormat.reads(get),
    Writes { o => JsString(o.status) }
  )

  def columnType(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[SlackIntegrationStatus, String](_.status, status => all.find(_.status == status).getOrElse { throw new IllegalStateException(s"Unkwown SlackIntegrationStatus: $status") })
  }
}

trait SlackIntegration {
  def slackUserId: SlackUserId
  def slackTeamId: SlackTeamId
  def slackChannelId: SlackChannelId
  def libraryId: Id[Library]
  def status: SlackIntegrationStatus
  def changedStatusAt: DateTime
}

object SlackIntegration {
  case class BrokenSlackIntegration(integration: SlackIntegration, token: Option[SlackAccessToken], cause: Option[SlackFail]) extends Exception(s"Found a broken Slack integration: token->$token, integration->$integration, cause->$cause")
  case class ForbiddenSlackIntegration(integration: SlackIntegration) extends Exception(s"Found a forbidden Slack integration: $integration")
}

sealed abstract class SlackIntegrationRequest
case class SlackIntegrationCreateRequest(
  requesterId: Id[User],
  slackUserId: SlackUserId,
  slackTeamId: SlackTeamId,
  slackChannelId: SlackChannelId,
  libraryId: Id[Library],
  status: SlackIntegrationStatus) extends SlackIntegrationRequest

case class ExternalSlackIntegrationModification(
  id: Either[PublicId[LibraryToSlackChannel], PublicId[SlackChannelToLibrary]],
  space: Option[ExternalLibrarySpace],
  status: Option[SlackIntegrationStatus])
object ExternalSlackIntegrationModification {
  private implicit val eitherIdFormat = EitherFormat(LibraryToSlackChannel.formatPublicId, SlackChannelToLibrary.formatPublicId)
  implicit val format: Format[ExternalSlackIntegrationModification] = (
    (__ \ 'id).format[Either[PublicId[LibraryToSlackChannel], PublicId[SlackChannelToLibrary]]] and
    (__ \ 'space).formatNullable[ExternalLibrarySpace] and
    (__ \ 'status).formatNullable[SlackIntegrationStatus]
  )(ExternalSlackIntegrationModification.apply, unlift(ExternalSlackIntegrationModification.unapply))
}

case class SlackIntegrationDeleteRequest(
  requesterId: Id[User],
  libToSlack: Set[Id[LibraryToSlackChannel]],
  slackToLib: Set[Id[SlackChannelToLibrary]]) extends SlackIntegrationRequest
@json case class SlackIntegrationDeleteResponse(deleted: Int)

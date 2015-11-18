package com.keepit.slack.models

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DataBaseComponent
import com.keepit.common.json.{ EitherFormat, EnumFormat }
import com.keepit.common.reflection.Enumerator
import com.keepit.model.{ LibrarySpace, Library, User }
import com.kifi.macros.json
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
  def ownerId: Id[User]
  def slackUserId: SlackUserId
  def slackTeamId: SlackTeamId
  def slackChannelId: Option[SlackChannelId]
  def slackChannelName: SlackChannelName
  def libraryId: Id[Library]
  def status: SlackIntegrationStatus
}

sealed abstract class SlackIntegrationRequest
case class SlackIntegrationCreateRequest(
  userId: Id[User],
  space: LibrarySpace,
  slackUserId: SlackUserId,
  slackTeamId: SlackTeamId,
  slackChannelId: Option[SlackChannelId],
  slackChannelName: SlackChannelName,
  libraryId: Id[Library]) extends SlackIntegrationRequest

case class SlackIntegrationModification(
  id: Either[PublicId[LibraryToSlackChannel], PublicId[SlackChannelToLibrary]],
  status: SlackIntegrationStatus)
object SlackIntegrationModification {
  private implicit val eitherIdFormat = EitherFormat(LibraryToSlackChannel.formatPublicId, SlackChannelToLibrary.formatPublicId)
  implicit val format: Format[SlackIntegrationModification] = (
    (__ \ 'id).format[Either[PublicId[LibraryToSlackChannel], PublicId[SlackChannelToLibrary]]] and
    (__ \ 'status).format[SlackIntegrationStatus]
  )(SlackIntegrationModification.apply, unlift(SlackIntegrationModification.unapply))
}

case class SlackIntegrationModifyRequest(
  requesterId: Id[User],
  libToSlack: Map[Id[LibraryToSlackChannel], SlackIntegrationStatus],
  slackToLib: Map[Id[SlackChannelToLibrary], SlackIntegrationStatus]) extends SlackIntegrationRequest
@json case class SlackIntegrationModifyResponse(modified: Int)

case class SlackIntegrationDeleteRequest(
  requesterId: Id[User],
  libToSlack: Set[Id[LibraryToSlackChannel]],
  slackToLib: Set[Id[SlackChannelToLibrary]]) extends SlackIntegrationRequest
@json case class SlackIntegrationDeleteResponse(deleted: Int)

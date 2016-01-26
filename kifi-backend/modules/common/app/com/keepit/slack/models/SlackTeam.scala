package com.keepit.slack.models

import com.keepit.common.db.Id
import com.keepit.model.Organization
import com.kifi.macros.json
import play.api.mvc.PathBindable

@json case class SlackTeamId(value: String)
object SlackTeamId {
  implicit def pathBinder = new PathBindable[SlackTeamId] {
    override def bind(key: String, value: String): Either[String, SlackTeamId] = Right(SlackTeamId(value))
    override def unbind(key: String, obj: SlackTeamId): String = obj.value
  }
}
@json case class SlackTeamName(value: String)
@json case class SlackTeamDomain(value: String)
@json case class SlackTeamEmailDomain(value: String)
@json case class InternalSlackTeamInfo(orgId: Option[Id[Organization]], teamName: SlackTeamName) // augment as needed


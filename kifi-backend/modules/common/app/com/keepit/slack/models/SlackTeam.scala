package com.keepit.slack.models

import com.keepit.common.db.Id
import com.keepit.model.Organization
import com.kifi.macros.json
import play.api.mvc.{ QueryStringBindable, PathBindable }

@json case class SlackTeamId(value: String)
object SlackTeamId {
  implicit val pathBinder = new PathBindable[SlackTeamId] {
    override def bind(key: String, value: String): Either[String, SlackTeamId] = Right(SlackTeamId(value))
    override def unbind(key: String, obj: SlackTeamId): String = obj.value
  }

  implicit def queryStringBinder(implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[SlackTeamId] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, SlackTeamId]] = {
      stringBinder.bind(key, params) map {
        case Right(value) => Right(SlackTeamId(value))
        case _ => Left("Unable to bind a SlackTeamId")
      }
    }
    override def unbind(key: String, teamId: SlackTeamId): String = {
      stringBinder.unbind(key, teamId.value)
    }
  }
}
@json case class SlackTeamName(value: String)
@json case class SlackTeamDomain(value: String)
@json case class SlackTeamEmailDomain(value: String)
@json case class InternalSlackTeamInfo(orgId: Option[Id[Organization]], teamName: SlackTeamName) // augment as needed


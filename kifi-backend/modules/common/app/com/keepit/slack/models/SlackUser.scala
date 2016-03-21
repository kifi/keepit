package com.keepit.slack.models
import com.kifi.macros.json
import play.api.mvc.{ QueryStringBindable, PathBindable }

@json case class SlackUserId(value: String) {
  def asChannel: SlackChannelId.User = SlackChannelId.User(value)
}
object SlackUserId {
  implicit val pathBinder = new PathBindable[SlackUserId] {
    override def bind(key: String, value: String): Either[String, SlackUserId] = Right(SlackUserId(value))
    override def unbind(key: String, obj: SlackUserId): String = obj.value
  }

  implicit def queryStringBinder(implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[SlackUserId] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, SlackUserId]] = {
      stringBinder.bind(key, params) map {
        case Right(value) => Right(SlackUserId(value))
        case _ => Left("Unable to bind a SlackUserId")
      }
    }
    override def unbind(key: String, userId: SlackUserId): String = {
      stringBinder.unbind(key, userId.value)
    }
  }
}
@json case class SlackUsername(value: String) {
  def asChannelName = SlackChannelName(this.value)
}

object SlackUsername {
  val slackbot = SlackUsername("slackbot")
  val kifibot = SlackUsername("kifi-bot")
  val doNotIngest = Set(slackbot, kifibot)
}

sealed trait SlackAccessToken {
  def token: String
}
@json case class SlackUserAccessToken(token: String) extends SlackAccessToken
@json case class SlackBotAccessToken(token: String) extends SlackAccessToken

case class SlackTokenWithScopes(token: SlackUserAccessToken, scopes: Set[SlackAuthScope])

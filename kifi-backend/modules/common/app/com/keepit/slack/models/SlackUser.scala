package com.keepit.slack.models
import com.kifi.macros.json

@json case class SlackUserId(value: String) {
  def asChannel: SlackChannelId.User = SlackChannelId.User(value)
}
@json case class SlackUsername(value: String)

object SlackUsername {
  val slackbot = SlackUsername("slackbot")
  val kifibot = SlackUsername("kifi-bot")
  val doNotIngest = Set(slackbot, kifibot)
}

@json case class SlackAccessToken(token: String)


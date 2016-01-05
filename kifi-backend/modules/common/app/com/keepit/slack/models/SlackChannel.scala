package com.keepit.slack.models

import com.kifi.macros.json
import play.api.libs.functional.syntax._
import play.api.libs.json._

@json case class SlackChannelId(value: String) // broad sense, can be channel, group or DM
@json case class SlackChannelName(value: String) // broad sense, can be channel, group or DM
@json case class SlackChannel(id: SlackChannelId, name: SlackChannelName)

// There is more stuff than just this returned
case class SlackChannelInfo(
  channelId: SlackChannelId,
  channelName: SlackChannelName,
  creator: SlackUserId,
  numMembers: Int)
object SlackChannelInfo {
  implicit val reads: Reads[SlackChannelInfo] = (
    (__ \ 'id).read[SlackChannelId] and
    (__ \ 'name).read[SlackChannelName] and
    (__ \ 'creator).read[SlackUserId] and
    (__ \ 'num_members).read[Int]
  )(SlackChannelInfo.apply _)
}

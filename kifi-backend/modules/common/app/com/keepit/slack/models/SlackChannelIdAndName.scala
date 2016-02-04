package com.keepit.slack.models

import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

@json case class SlackChannelId(value: String) // broad sense, can be channel, group or DM
@json case class SlackChannelName(value: String) // broad sense, can be channel, group or DM
@json case class SlackChannelIdAndName(id: SlackChannelId, name: SlackChannelName)
@json case class SlackChannelTopic(value: String)
@json case class SlackChannelPurpose(value: String)

// There is more stuff than just this returned
case class SlackChannelInfo(
    channelId: SlackChannelId,
    channelName: SlackChannelName,
    creator: SlackUserId,
    createdAt: SlackTimestamp,
    isArchived: Boolean,
    isGeneral: Boolean,
    numMembers: Int,
    topic: Option[SlackChannelTopic],
    purpose: Option[SlackChannelPurpose]) {
  def channelIdAndName = SlackChannelIdAndName(channelId, channelName)
}
object SlackChannelInfo {
  implicit val reads: Reads[SlackChannelInfo] = (
    (__ \ 'id).read[SlackChannelId] and
    (__ \ 'name).read[String].map(name => SlackChannelName("#" + name)) and
    (__ \ 'creator).read[SlackUserId] and
    (__ \ 'created).read[Long].map(t => SlackTimestamp(t.toString)) and
    (__ \ 'is_archived).read[Boolean] and
    (__ \ 'is_general).read[Boolean] and
    (__ \ 'num_members).read[Int] and
    (__ \ 'topic \ 'value).readNullable[SlackChannelTopic].map(_.filter(_.value.nonEmpty)) and
    (__ \ 'purpose \ 'value).readNullable[SlackChannelPurpose].map(_.filter(_.value.nonEmpty))
  )(SlackChannelInfo.apply _)
}

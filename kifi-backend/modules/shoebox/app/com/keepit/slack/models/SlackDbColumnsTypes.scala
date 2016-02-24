package com.keepit.slack.models

import com.keepit.common.db.slick.DataBaseComponent
import play.api.libs.json.{ JsArray, Json }

object SlackDbColumnTypes {
  def userId(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[SlackUserId, String](_.value, SlackUserId(_))
  }
  def userIdSet(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[Set[SlackUserId], String](
      ids => Json.stringify(Json.toJson(ids)),
      str => if (str.isEmpty) Set.empty else Json.parse(str).as[Set[SlackUserId]]
    )
  }
  def username(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[SlackUsername, String](_.value, SlackUsername(_))
  }
  def teamId(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[SlackTeamId, String](_.value, SlackTeamId(_))
  }
  def teamName(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[SlackTeamName, String](_.value, SlackTeamName(_))
  }
  def channelId(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[SlackChannelId, String](_.value, SlackChannelId(_))
  }
  def channelIdSet(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[Set[SlackChannelId], String](
      ids => Json.stringify(Json.toJson(ids)),
      str => Json.parse(str).as[Set[SlackChannelId]]
    )
  }

  def channelName(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[SlackChannelName, String](_.value, SlackChannelName(_))
  }

  def timestamp(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[SlackTimestamp, String](_.value, SlackTimestamp(_))
  }
}

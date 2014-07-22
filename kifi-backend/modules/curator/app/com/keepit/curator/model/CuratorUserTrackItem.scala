package com.keepit.curator.model

import com.keepit.common.db.{ Model, Id }
import com.keepit.common.time._
import com.keepit.model.User
import org.joda.time.DateTime

case class CuratorUserTrackItem(
    id: Option[Id[CuratorUserTrackItem]] = None,
    userId: Id[User],
    lastSeen: DateTime = currentDateTime) extends Model[CuratorUserTrackItem] {

  def withId(id: Id[CuratorUserTrackItem]): CuratorUserTrackItem = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): CuratorUserTrackItem = this.copy(lastSeen = updateTime)
}

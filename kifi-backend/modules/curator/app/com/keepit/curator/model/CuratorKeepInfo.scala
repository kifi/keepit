package com.keepit.curator.model

import com.keepit.common.db.{ Model, Id, ModelWithState, State }
import com.keepit.model.{ NormalizedURI, User, Keep }
import com.keepit.common.time._

import org.joda.time.DateTime

case class CuratorKeepInfo(
  id: Option[Id[CuratorKeepInfo]] = None,
  createdAt: DateTime = currentDateTime,
  updateAt: DateTime = currentDateTime,
  uriId: Id[NormalizedURI],
  userId: Id[User],
  keepId: Id[Keep],
  isPrivate: Boolean,
  state: State[CuratorKeepInfo])
    extends Model[CuratorKeepInfo] with ModelWithState[CuratorKeepInfo] {

  def withId(id: Id[CuratorKeepInfo]): CuratorKeepInfo = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): CuratorKeepInfo = this.copy(updateAt = updateTime)
}

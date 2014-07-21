package com.keepit.curator.model

import com.keepit.common.db.{ Id, State, ModelWithState, Model }
import com.keepit.common.time._
import com.keepit.model.{ Keep, User, NormalizedURI }
import org.joda.time.DateTime

case class CuratorUriInfo(
  id: Option[Id[CuratorUriInfo]] = None,
  createdAt: DateTime = currentDateTime,
  updateAt: DateTime = currentDateTime,
  uriId: Id[NormalizedURI],
  score: Double) extends Model[CuratorUriInfo] {

  def withId(id: Id[CuratorUriInfo]): CuratorUriInfo = this.copy(id = Some(id))

  def withUpdateTime(updateTime: DateTime): CuratorUriInfo = this.copy(updateAt = updateTime)
}
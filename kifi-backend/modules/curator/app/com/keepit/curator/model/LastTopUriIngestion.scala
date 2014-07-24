package com.keepit.curator.model

import com.keepit.common.db.{ Model, Id }
import com.keepit.common.time._
import com.keepit.model.User
import org.joda.time.DateTime

case class LastTopUriIngestion(
    id: Option[Id[LastTopUriIngestion]] = None,
    createdAt: Option[DateTime] = currentDateTime,
    updatedAt: Option[DateTime] = currentDateTime,
    userId: Id[User],
    lastIngestionTime: DateTime = currentDateTime) extends Model[LastTopUriIngestion] {

  def withId(id: Id[LastTopUriIngestion]): LastTopUriIngestion = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime): LastTopUriIngestion = this.copy(lastIngestionTime = updateTime)
}

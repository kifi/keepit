package com.keepit.cortex.dbmodel

import com.keepit.common.db.{ Model, Id }
import com.keepit.common.time._
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import org.joda.time.DateTime

case class LDAInfo(
    id: Option[Id[LDAInfo]],
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    version: ModelVersion[DenseLDA],
    dimension: Int,
    topicId: Int,
    topicName: String = LDAInfo.DEFUALT_NAME,
    isNameable: Boolean = true,
    isUsable: Boolean = true,
    numOfDocs: Int = 0) extends Model[LDAInfo] {
  def withId(id: Id[LDAInfo]): LDAInfo = copy(id = Some(id))
  def withUpdateTime(time: DateTime): LDAInfo = copy(updatedAt = time)
}

object LDAInfo {
  val DEFUALT_NAME = "n/a"
}

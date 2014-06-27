package com.keepit.cortex.dbmodel

import com.keepit.common.db.Id
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.cortex.core.StatModelName
import com.keepit.common.db.Model
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.core.StatModel
import com.keepit.common.db.SequenceNumber

case class FeatureCommitInfo(
  id: Option[Id[FeatureCommitInfo]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime= currentDateTime,
  modelName: StatModelName,
  modelVersion: Int,
  seq: Long
) extends Model[FeatureCommitInfo]{
  def withId(id: Id[FeatureCommitInfo]) = copy(id = Some(id))
  def withUpdateTime(time: DateTime) = copy(updatedAt = time)
  def withSeq(newSeq: Long) = copy(seq = newSeq)
}

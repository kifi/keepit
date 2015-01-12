package com.keepit.cortex.dbmodel

import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import org.joda.time.DateTime
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model._

case class LDARelatedLibrary(
    id: Option[Id[LDARelatedLibrary]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    version: ModelVersion[DenseLDA],
    sourceId: Id[Library],
    destId: Id[Library],
    weight: Float,
    state: State[LDARelatedLibrary] = LDARelatedLibraryStates.ACTIVE) extends ModelWithState[LDARelatedLibrary] {
  def withId(id: Id[LDARelatedLibrary]): LDARelatedLibrary = copy(id = Some(id))
  def withUpdateTime(now: DateTime): LDARelatedLibrary = copy(updatedAt = now)
}

object LDARelatedLibraryStates extends States[LDARelatedLibrary]

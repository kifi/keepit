package com.keepit.cortex.dbmodel

import com.keepit.common.db.{ States, ModelWithState, State, Id }
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.{ LDATopic, DenseLDA }
import com.keepit.model.Library
import org.joda.time.DateTime
import com.keepit.common.time._

case class LibraryTopicMean(value: Array[Float])

case class LibraryLDATopic(
    id: Option[Id[LibraryLDATopic]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    libraryId: Id[Library],
    version: ModelVersion[DenseLDA],
    numOfEvidence: Int,
    topic: Option[LibraryTopicMean],
    state: State[LibraryLDATopic],
    firstTopic: Option[LDATopic] = None,
    secondTopic: Option[LDATopic] = None,
    thirdTopic: Option[LDATopic] = None,
    firstTopicScore: Option[Float] = None,
    entropy: Option[Float] = None) extends ModelWithState[LibraryLDATopic] {
  def withId(id: Id[LibraryLDATopic]): LibraryLDATopic = copy(id = Some(id))
  def withUpdateTime(time: DateTime): LibraryLDATopic = copy(updatedAt = time)
}

object LibraryLDATopicStates extends States[LibraryLDATopic] {
  val NOT_APPLICABLE = State[LibraryLDATopic]("not_applicable")
}

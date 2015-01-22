package com.keepit.cortex.dbmodel

import com.keepit.common.db.{ States, ModelWithState, State, Id }
import com.keepit.common.time._
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.{ LDATopic, DenseLDA }
import org.joda.time.DateTime

case class PersonaFeature(
    id: Option[Id[PersonaFeature]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    personaId: Id[Persona],
    version: ModelVersion[DenseLDA],
    feature: UserTopicMean,
    firstTopic: LDATopic,
    secondTopic: LDATopic,
    thirdTopic: LDATopic,
    state: State[PersonaFeature] = PersonaFeatureStates.ACTIVE) extends ModelWithState[PersonaFeature] {
  def withId(id: Id[PersonaFeature]): PersonaFeature = copy(id = Some(id))
  def withUpdateTime(now: DateTime): PersonaFeature = copy(updatedAt = now)
}

object PersonaFeatureStates extends States[PersonaFeature]

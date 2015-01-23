package com.keepit.cortex.dbmodel

import com.keepit.common.db.{ States, ModelWithState, State, Id }
import com.keepit.common.time._
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.{ LDATopic, DenseLDA }
import com.keepit.model.Persona
import org.joda.time.DateTime

case class PersonaLDAFeature(
    id: Option[Id[PersonaLDAFeature]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    personaId: Id[Persona],
    version: ModelVersion[DenseLDA],
    feature: UserTopicMean,
    firstTopic: LDATopic,
    secondTopic: LDATopic,
    thirdTopic: LDATopic,
    state: State[PersonaLDAFeature] = PersonaFeatureStates.ACTIVE) extends ModelWithState[PersonaLDAFeature] {
  def withId(id: Id[PersonaLDAFeature]): PersonaLDAFeature = copy(id = Some(id))
  def withUpdateTime(now: DateTime): PersonaLDAFeature = copy(updatedAt = now)
}

object PersonaFeatureStates extends States[PersonaLDAFeature]

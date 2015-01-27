package com.keepit.cortex.dbmodel

import com.keepit.common.db.{ States, ModelWithState, State, Id }
import com.keepit.common.time._
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.{ LDATopic, DenseLDA }
import com.keepit.model.Persona
import org.joda.time.DateTime
import com.keepit.cortex.utils.MatrixUtils

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
  def withFeature(feat: UserTopicMean): PersonaLDAFeature = {
    val (first, second, third) = MatrixUtils.argmax3(feat.mean)
    copy(feature = feat, firstTopic = LDATopic(first), secondTopic = LDATopic(second), thirdTopic = LDATopic(third))
  }
}

object PersonaFeatureStates extends States[PersonaLDAFeature]

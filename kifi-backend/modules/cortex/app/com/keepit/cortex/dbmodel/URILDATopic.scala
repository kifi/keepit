package com.keepit.cortex.dbmodel

import org.joda.time.DateTime
import com.keepit.common.db._
import com.keepit.model.NormalizedURI
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.common.time._
import com.keepit.cortex.models.lda.LDATopic
import com.keepit.cortex.models.lda.LDATopicFeature
import com.keepit.cortex.models.lda.SparseTopicRepresentation


case class URILDATopic(
  id: Option[Id[URILDATopic]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  uriId: Id[NormalizedURI],
  uriSeq: SequenceNumber[NormalizedURI],
  version: ModelVersion[DenseLDA],
  firstTopic: Option[LDATopic] = None,
  secondTopic: Option[LDATopic] = None,
  thirdTopic: Option[LDATopic] = None,
  sparseFeature: Option[SparseTopicRepresentation] = None,
  feature: Option[LDATopicFeature] = None,
  state: State[URILDATopic]
) extends ModelWithState[URILDATopic] {
  def withId(id: Id[URILDATopic]): URILDATopic = copy(id = Some(id))
  def withUpdateTime(time: DateTime): URILDATopic = copy(updatedAt = time)
}

object URILDATopicStates extends States[URILDATopic] {
  val NOT_APPLICABLE = State[URILDATopic]("not_applicable")
}

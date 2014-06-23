package com.keepit.cortex.dbmodel

import org.joda.time.DateTime
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.DenseLDA
import com.keepit.common.db.State
import com.keepit.common.db.SequenceNumber
import com.keepit.common.db.States
import com.keepit.common.db.ModelWithState

case class URILDATopic(
  id: Option[Id[URILDATopic]],
  createdAt: DateTime,
  updatedAt: DateTime,
  uriId: Id[NormalizedURI],
  uriState: State[NormalizedURI],
  uriSeq: SequenceNumber[NormalizedURI],
  version: ModelVersion[DenseLDA],
  feature: Array[Byte],
  state: State[URILDATopic]
) extends ModelWithState[URILDATopic] {
  def withId(id: Id[URILDATopic]): URILDATopic = copy(id = Some(id))
  def withUpdateTime(time: DateTime): URILDATopic = copy(updatedAt = time)
}

object URILDATopicStates extends States[URILDATopic]
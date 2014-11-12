package com.keepit.common.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.features.Document
import com.keepit.cortex.models.lda._

@Singleton
class LDARepresenterCommander @Inject() (
    wordRep: MultiVersionedLDAWordRepresenter,
    docRep: MultiVersionedLDADocRepresenter) {

  def wordTopic(word: String)(implicit version: ModelVersion[DenseLDA]): Option[Array[Float]] = {
    val rep = wordRep.getRepresenter(version).get
    rep(word).map { _.vectorize }
  }

  def docTopic(doc: Document)(implicit version: ModelVersion[DenseLDA]): Option[Array[Float]] = {
    val rep = docRep.getRepresenter(version).get
    rep(doc).map { _.vectorize }
  }

}

package com.keepit.common.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.cortex.features.Document
import com.keepit.cortex.models.lda.{ LDADocRepresenter, LDAWordRepresenter }

@Singleton
class LDARepresenterCommander @Inject() (
    wordRep: LDAWordRepresenter,
    docRep: LDADocRepresenter) {

  def wordTopic(word: String): Option[Array[Float]] = {
    wordRep(word).map { _.vectorize }
  }

  def docTopic(doc: Document): Option[Array[Float]] = {
    docRep(doc).map { _.vectorize }
  }

}

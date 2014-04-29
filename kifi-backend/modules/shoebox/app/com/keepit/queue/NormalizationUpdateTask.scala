package com.keepit.queue

import com.keepit.common.queue.SimpleQueue
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.normalizer.NormalizationCandidate
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class NormalizationUpdateTask(uriId:Id[NormalizedURI], isNew:Boolean, candidates:Seq[NormalizationCandidate])

object NormalizationUpdateTask {
  implicit val format = (
      (__ \ 'uriId).format(Id.format[NormalizedURI]) and
      (__ \ 'isNew).format[Boolean] and
      (__ \ 'candidates).format[Seq[NormalizationCandidate]]
    )(NormalizationUpdateTask.apply _, unlift(NormalizationUpdateTask.unapply))
}


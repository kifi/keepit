package com.keepit.serializer

import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.common.db.SequenceNumber

object CollectionTupleSerializer {
  implicit val collectionTupleFormat = (
    (__ \ 'collId).format(Id.format[Collection]) and
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'seq).format(SequenceNumber.format)
  ).tupled
}
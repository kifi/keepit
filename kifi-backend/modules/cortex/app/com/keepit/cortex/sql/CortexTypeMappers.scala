package com.keepit.cortex.sql

import com.keepit.common.db.slick.DataBaseComponent
import com.keepit.cortex.core._
import java.sql.Blob
import javax.sql.rowset.serial.SerialBlob
import com.keepit.cortex.store.StoreUtil.FloatArrayFormmater
import com.keepit.cortex.models.lda.LDATopic
import com.keepit.cortex.models.lda.SparseTopicRepresentation
import play.api.libs.json._
import com.keepit.cortex.models.lda.LDATopicFeature

trait CortexTypeMappers {  self: {val db: DataBaseComponent} =>
  import db.Driver.simple._

  implicit def modelVersionMapper[M <: StatModel] = MappedColumnType.base[ModelVersion[M], Int](_.version, ModelVersion[M])

  implicit def ldaTopicMapper = MappedColumnType.base[LDATopic, Int](_.index, LDATopic(_))

  implicit def ldaTopicFeatureMapper = MappedColumnType.base[LDATopicFeature, Blob](
    { feat => new SerialBlob(FloatArrayFormmater.toBinary(feat.value))},
    { blob => val len = blob.length().toInt; val arr = FloatArrayFormmater.fromBinary(blob.getBytes(0, len)); LDATopicFeature(arr) }
  )

  implicit def sparseTopicRepresentationMapper = MappedColumnType.base[SparseTopicRepresentation, String](
    { topic => Json.stringify(Json.toJson(topic)) },
    { jstr => val js = Json.parse(jstr); js.as[SparseTopicRepresentation] }
  )
}

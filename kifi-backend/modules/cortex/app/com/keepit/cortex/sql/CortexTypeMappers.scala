package com.keepit.cortex.sql

import com.keepit.common.db.slick.DataBaseComponent
import com.keepit.cortex.core._
import java.sql.Blob
import javax.sql.rowset.serial.SerialBlob
import com.keepit.cortex.dbmodel.{ LibraryTopicMean, UserTopicVar, UserTopicMean }
import com.keepit.cortex.store.StoreUtil.FloatArrayFormmater
import com.keepit.cortex.models.lda.LDATopic
import com.keepit.cortex.models.lda.SparseTopicRepresentation
import play.api.libs.json._
import com.keepit.cortex.models.lda.LDATopicFeature
import com.keepit.cortex.core.StatModelName

trait CortexTypeMappers { self: { val db: DataBaseComponent } =>
  import db.Driver.simple._

  implicit def modelVersionMapper[M <: StatModel] = MappedColumnType.base[ModelVersion[M], Int](_.version, ModelVersion[M])

  implicit def statModelName = MappedColumnType.base[StatModelName, String](_.name, StatModelName(_))

  implicit def ldaTopicFeatureMapper = MappedColumnType.base[LDATopicFeature, Blob](
    { feat => new SerialBlob(FloatArrayFormmater.toBinary(feat.value)) },
    { blob => val len = blob.length().toInt; val arr = FloatArrayFormmater.fromBinary(blob.getBytes(1, len)); LDATopicFeature(arr) }
  )

  implicit def userTopicMeanMapper = MappedColumnType.base[UserTopicMean, Blob](
    { topic => new SerialBlob(FloatArrayFormmater.toBinary(topic.mean)) },
    { blob => val len = blob.length().toInt; val arr = FloatArrayFormmater.fromBinary(blob.getBytes(1, len)); UserTopicMean(arr) }
  )

  implicit def userTopicVarMapper = MappedColumnType.base[UserTopicVar, Blob](
    { topicVar => new SerialBlob(FloatArrayFormmater.toBinary(topicVar.value)) },
    { blob => val len = blob.length().toInt; val arr = FloatArrayFormmater.fromBinary(blob.getBytes(1, len)); UserTopicVar(arr) }
  )

  implicit def libraryTopicMeanMapper = MappedColumnType.base[LibraryTopicMean, Blob](
    { topic => new SerialBlob(FloatArrayFormmater.toBinary(topic.value)) },
    { blob => val len = blob.length().toInt; val arr = FloatArrayFormmater.fromBinary(blob.getBytes(1, len)); LibraryTopicMean(arr) }
  )

  implicit def sparseTopicRepresentationMapper = MappedColumnType.base[SparseTopicRepresentation, String](
    { topic => Json.stringify(Json.toJson(topic)) },
    { jstr => Json.parse(jstr).as[SparseTopicRepresentation] }
  )
}

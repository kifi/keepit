package com.keepit.cortex.sql

import com.keepit.common.db.slick.DataBaseComponent
import com.keepit.cortex.core._
import java.sql.Blob
import javax.sql.rowset.serial.SerialBlob
import com.keepit.cortex.store.StoreUtil.FloatArrayFormmater

trait CortexTypeMappers {  self: {val db: DataBaseComponent} =>
  import db.Driver.simple._

  implicit def modelVersionMapper[M <: StatModel] = MappedColumnType.base[ModelVersion[M], Int](_.version, ModelVersion[M])

  implicit def floatArrayMapper = MappedColumnType.base[Array[Float], Blob](
    { floats => new SerialBlob(FloatArrayFormmater.toBinary(floats))},
    { blob => val len = blob.length().toInt; FloatArrayFormmater.fromBinary(blob.getBytes(0, len)) }
  )

}
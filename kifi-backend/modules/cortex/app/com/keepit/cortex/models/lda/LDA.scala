package com.keepit.cortex.models.lda

import com.keepit.cortex.core.StatModel
import com.keepit.cortex.core.BinaryFormatter
import java.io._
import com.keepit.cortex.store.StoreUtil


trait LDA extends StatModel

// mapper: word -> topic vector
case class DenseLDA(dimension: Int, mapper: Map[String, Array[Float]]) extends LDA

object DenseLDAFormatter extends BinaryFormatter[DenseLDA] {

  def toBinary(lda: DenseLDA): Array[Byte] = StoreUtil.DenseWordVecFormatter.toBinary(lda.dimension, lda.mapper)
  def fromBinary(bytes: Array[Byte]): DenseLDA = {
    val (dim, mapper) = StoreUtil.DenseWordVecFormatter.fromBinary(bytes)
    DenseLDA(dim, mapper)
  }
}

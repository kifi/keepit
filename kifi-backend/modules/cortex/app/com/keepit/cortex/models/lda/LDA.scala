package com.keepit.cortex.models.lda

import com.amazonaws.services.s3.AmazonS3
import com.google.inject.Inject
import com.google.inject.Singleton
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.S3Bucket
import com.keepit.cortex.core._
import com.keepit.cortex.features._
import com.keepit.cortex.plugins.FeatureUpdater
import com.keepit.cortex.plugins.URIPuller
import com.keepit.cortex.store._
import com.keepit.model.NormalizedURI
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.keepit.search.Lang
import com.keepit.cortex.plugins.URIFeatureUpdater
import com.keepit.cortex.plugins.DataPuller
import com.keepit.cortex._

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

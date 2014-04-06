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

case class LDAWordRepresenter(val version: ModelVersion[DenseLDA], lda: DenseLDA) extends HashMapWordRepresenter[DenseLDA](lda.dimension, lda.mapper)

case class LDADocRepresenter(wordRep: LDAWordRepresenter) extends NaiveSumDocRepresenter(wordRep){
  override def normalize(vec: Array[Float]): Array[Float] = {
    val s = vec.sum
    vec.map{ x => x/s}
  }
}

case class LDAURIRepresenter(docRep: LDADocRepresenter, articleStore: ArticleStore) extends BaseURIFeatureRepresenter(docRep, articleStore) {

  override def isDefinedAt(article: Article): Boolean = article.contentLang == Some(Lang("en"))

  override def toDocument(article: Article): Document = {
    Document(article.content.split(" "))    // TODO(yingjie): Lucene tokenize
  }
}

trait LDAURIFeatureStore extends FloatVecFeatureStore[Id[NormalizedURI], NormalizedURI, DenseLDA]

class S3BlobLDAURIFeatureStore(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog)
  extends S3BlobFloatVecFeatureStore[Id[NormalizedURI], NormalizedURI, DenseLDA] with LDAURIFeatureStore{
  val prefix = "uri_features/dense_lda/"
  override def keyPrefix() = prefix
}

class InMemoryLDAURIFeatureStore extends InMemoryFloatVecFeatureStore[Id[NormalizedURI], NormalizedURI, DenseLDA] with LDAURIFeatureStore

trait LDAURIFeatureCommitStore extends CommitInfoStore[NormalizedURI, DenseLDA]

class S3LDAURIFeatureCommitStore(bucketName: S3Bucket,
  amazonS3Client: AmazonS3,
  accessLog: AccessLog) extends S3CommitInfoStore[NormalizedURI, DenseLDA](bucketName, amazonS3Client, accessLog){
  val prefix = "commit_info/dense_lda_uri_features/"
  override def keyPrefix() = prefix
}

class InMemoryLDAURIFeatureCommitStore extends InMemoryCommitInfoStore[NormalizedURI, DenseLDA] with LDAURIFeatureCommitStore

@Singleton
class LDAURIFeatureUpdater @Inject()(
  representer: LDAURIRepresenter,
  featureStore: LDAURIFeatureStore,
  commitStore: LDAURIFeatureCommitStore,
  uriPuller: DataPuller[NormalizedURI]
) extends URIFeatureUpdater[DenseLDA](representer, featureStore, commitStore, uriPuller)


package com.keepit.cortex.store

import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.ObjectStore
import com.keepit.common.store.S3Bucket
import com.keepit.common.store.S3JsonStore
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.core.StatModel
import play.api.libs.json.Format
import com.keepit.common.store.InMemoryObjectStore

trait CommitInfoStore[T, M <: StatModel] extends ObjectStore[CommitInfoKey[T, M], CommitInfo[T, M]]

abstract class S3CommitInfoStore[T, M <: StatModel](
    val bucketName: S3Bucket,
    val amazonS3Client: AmazonS3,
    val accessLog: AccessLog,
    val formatter: Format[CommitInfo[T, M]] = CommitInfo.format[T, M]) extends S3JsonStore[CommitInfoKey[T, M], CommitInfo[T, M]] with CommitInfoStore[T, M] {
  val prefix: String
  override def keyPrefix() = prefix
}

class InMemoryCommitInfoStore[T, M <: StatModel] extends InMemoryObjectStore[CommitInfoKey[T, M], CommitInfo[T, M]] with CommitInfoStore[T, M]

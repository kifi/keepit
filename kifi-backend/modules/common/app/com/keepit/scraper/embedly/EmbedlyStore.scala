package com.keepit.scraper.embedly

import com.amazonaws.services.s3.AmazonS3
import com.google.inject.Singleton
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.{InMemoryObjectStore, ObjectStore, S3Bucket, S3JsonStore}
import com.keepit.model.NormalizedURI

import play.api.libs.json.Format


trait EmbedlyStore extends ObjectStore[Id[NormalizedURI], ExtendedEmbedlyInfo]

class S3EmbedlyStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog, val formatter: Format[ExtendedEmbedlyInfo] = ExtendedEmbedlyInfo.format)
  extends S3JsonStore[Id[NormalizedURI], ExtendedEmbedlyInfo] with EmbedlyStore

class InMemoryArticleStoreImpl extends InMemoryObjectStore[Id[NormalizedURI], ExtendedEmbedlyInfo] with EmbedlyStore

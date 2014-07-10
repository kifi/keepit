package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.model.NormalizedURI
import com.amazonaws.services.s3._
import com.keepit.common.store._
import play.api.libs.json.Format

trait ArticleStore extends ObjectStore[Id[NormalizedURI], Article]

class S3ArticleStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog, val formatter: Format[Article] = Article.format)
  extends S3JsonStore[Id[NormalizedURI], Article] with ArticleStore

class InMemoryArticleStoreImpl extends InMemoryObjectStore[Id[NormalizedURI], Article] with ArticleStore

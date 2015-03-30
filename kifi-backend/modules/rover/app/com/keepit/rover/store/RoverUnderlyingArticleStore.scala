package com.keepit.rover.store

import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.{ InMemoryObjectStore, S3JsonStore, S3Bucket, ObjectStore }
import com.keepit.rover.article.Article
import com.keepit.rover.model.ArticleKey
import play.api.libs.json.Format

private[store] class ArticleStoreKey(key: ArticleKey[_]) {
  override def toString = s"${key.uriId.id}/${key.kind.typeCode}/${key.version.major.value}/${key.version.minor.value}"
}

private[store] object ArticleStoreKey {
  implicit def apply[A <: Article](key: ArticleKey[A]): ArticleStoreKey = new ArticleStoreKey(key)
}

private[store] trait RoverUnderlyingArticleStore extends ObjectStore[ArticleStoreKey, Article]

private[store] class S3RoverUnderlyingArticleStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog)
    extends S3JsonStore[ArticleStoreKey, Article] with RoverUnderlyingArticleStore {
  val formatter: Format[Article] = Article.format
}

private[store] class InMemoryRoverUnderlyingArticleStoreImpl extends InMemoryObjectStore[ArticleStoreKey, Article] with RoverUnderlyingArticleStore

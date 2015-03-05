package com.keepit.rover.store

import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.db.{ VersionNumber, Id }
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.{ InMemoryObjectStore, S3JsonStore, S3Bucket, ObjectStore }
import com.keepit.model.NormalizedURI
import com.keepit.rover.article.{ Article, ArticleKind }
import play.api.libs.json.Format

case class ArticleKey(uriId: Id[NormalizedURI], kind: ArticleKind[_ <: Article], major: VersionNumber[Article], minor: VersionNumber[Article]) {
  def toKey: String = s"${uriId.id}/${kind.typeCode}/${major.value}/${minor.value}" // do not change this unless you actually want to break it
  override def toString = toKey
}

trait RoverArticleStore extends ObjectStore[ArticleKey, Article]

class S3RoverArticleStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog)
    extends S3JsonStore[ArticleKey, Article] with RoverArticleStore {
  val formatter: Format[Article] = Article.format
}

class InMemoryRoverArticleStoreImpl extends InMemoryObjectStore[ArticleKey, Article] with RoverArticleStore

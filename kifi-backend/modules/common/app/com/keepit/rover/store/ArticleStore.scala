package com.keepit.rover.store

import com.amazonaws.services.s3.AmazonS3
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.{ InMemoryObjectStore, S3JsonStore, S3Bucket, ObjectStore }
import com.keepit.model.NormalizedURI
import com.keepit.rover.article.{ Article, ArticleKind }
import com.keepit.rover.model.ArticleVersion
import play.api.libs.json.Format

import scala.concurrent.{ ExecutionContext, Future }
import scala.reflect.ClassTag

case class ArticleKey[A <: Article](uriId: Id[NormalizedURI], kind: ArticleKind[A], version: ArticleVersion) {
  def toKey: String = s"${uriId.id}/${kind.typeCode}/${version.major.value}/${version.minor.value}" // do not change this unless you actually want to break it
  override def toString = toKey
}

case class InconsistentArticleTypeException[A <: Article](key: ArticleKey[A], article: Article)
  extends Throwable(s"Found inconsistent article for key $key: $article")

trait RoverUnderlyingArticleStore extends ObjectStore[ArticleKey[_], Article]

class S3RoverUnderlyingArticleStoreImpl(val bucketName: S3Bucket, val amazonS3Client: AmazonS3, val accessLog: AccessLog)
    extends S3JsonStore[ArticleKey[_], Article] with RoverUnderlyingArticleStore {
  val formatter: Format[Article] = Article.format
}

class InMemoryRoverUnderlyingArticleStoreImpl extends InMemoryObjectStore[ArticleKey[_], Article] with RoverUnderlyingArticleStore

@Singleton
class RoverArticleStore @Inject() (underlying: RoverUnderlyingArticleStore, implicit val executionContext: ExecutionContext) {
  // The article type is stored both within its key and the article itself.
  // This wrapper enforces consistency and provides APIs with more specific typing.

  def get[A <: Article](key: ArticleKey[A])(implicit classTag: ClassTag[A]): Future[Option[A]] = {
    SafeFuture {
      underlying.get(key).map {
        case expectedArticle: A => expectedArticle
        case unexpectedArticle => throw new InconsistentArticleTypeException(key, unexpectedArticle)
      }
    }
  }

  def add[A <: Article](uriId: Id[NormalizedURI], previousVersion: Option[ArticleVersion], article: A)(implicit kind: ArticleKind[A]): Future[ArticleKey[A]] = {
    SafeFuture {
      val key = ArticleKey(uriId, kind, ArticleVersion.next[A](previousVersion))
      underlying += (key -> article)
      key
    }
  }
}

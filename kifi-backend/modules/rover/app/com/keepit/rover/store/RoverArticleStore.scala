package com.keepit.rover.store

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.rover.article.{ Article, ArticleKind }
import com.keepit.rover.model.{ ArticleKey, ArticleVersion }

import scala.concurrent.{ ExecutionContext, Future }
import scala.reflect.ClassTag

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

case class InconsistentArticleTypeException[A <: Article](key: ArticleKey[A], article: Article)
  extends Throwable(s"Found inconsistent article for key $key: $article")

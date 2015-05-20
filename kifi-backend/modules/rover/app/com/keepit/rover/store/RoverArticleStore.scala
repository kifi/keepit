package com.keepit.rover.store

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.service.RequestConsolidator
import com.keepit.model.NormalizedURI
import com.keepit.rover.article.{ Article, ArticleKind }
import com.keepit.rover.model.{ ArticleVersionProvider, ArticleKey, ArticleVersion }
import scala.concurrent.duration._
import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class RoverArticleStore @Inject() (underlying: RoverUnderlyingArticleStore, private implicit val executionContext: ExecutionContext) {
  // The article type is stored both within its key and the article itself.
  // This wrapper enforces consistency and provides APIs with more specific typing.

  private val consolidate = new RequestConsolidator[ArticleStoreKey, Option[Article]](30 minutes)

  def get[A <: Article](key: ArticleKey[A]): Future[Option[A]] = {
    consolidate(key)(storeKey => SafeFuture {
      underlying.syncGet(storeKey)
    }).imap { articleOpt =>
      articleOpt.map(_.asExpected(key.kind))
    }
  }

  def get[A <: Article](keyOpt: Option[ArticleKey[A]]): Future[Option[A]] = keyOpt.map(get[A]) getOrElse Future.successful(None)

  def add[A <: Article](uriId: Id[NormalizedURI], previousVersion: Option[ArticleVersion], article: A)(implicit kind: ArticleKind[A]): Future[ArticleKey[A]] = {
    SafeFuture {
      val key = ArticleKey(uriId, kind, ArticleVersionProvider.next[A](previousVersion))
      val storeKey = ArticleStoreKey(key)
      underlying += (storeKey -> article)
      consolidate.set(storeKey, Future.successful(Some(article)))
      key
    }
  }
}

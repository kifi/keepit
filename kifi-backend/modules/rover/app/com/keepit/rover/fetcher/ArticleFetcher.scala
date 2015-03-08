package com.keepit.rover.fetcher

import com.google.inject.{ Inject, Singleton }
import com.keepit.rover.article.{ ArticleKind, Article }

import scala.concurrent.Future

trait ArticleFetcher[A <: Article] {
  def fetch(url: String): Future[A]
}

trait ArticleFetcherFactory {
  def apply[A <: Article](implicit kind: ArticleKind[A]): ArticleFetcher[A]
}

@Singleton
class ArticleFetcherFactoryImpl @Inject() () extends ArticleFetcherFactory {
  def apply[A <: Article](implicit kind: ArticleKind[A]): ArticleFetcher[A] = kind match {
    case _ => ???
  }
}

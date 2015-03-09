package com.keepit.rover.article

import com.google.inject.{ Inject, Singleton }

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

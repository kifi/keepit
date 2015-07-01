package com.keepit.rover.article.fetcher

import com.keepit.rover.article.{ Article, ArticleKind }
import com.keepit.rover.test.RoverTestInjector

import scala.concurrent.{ ExecutionContext, Await }
import scala.concurrent.duration._

trait ArticleFetcherTest[A <: Article] extends RoverTestInjector {

  type FetcherType <: ArticleFetcher[A]
  val fetcherClass: Class[FetcherType]

  val articleKind: ArticleKind[A]
  val articleFetchDuration = 10.seconds

  lazy val articleFetcher: FetcherType =
    withInjector(FileHttpFetcherModule()) { implicit injector =>
      injector.getInstance(fetcherClass)
    }

  def fetch(file: String)(implicit ec: ExecutionContext): A = {

    def mkRequest(file: String): ArticleFetchRequest[A] = {
      val fileLocation = s"test/com/keepit/rover/article/fetcher/fixtures/$file"
      ArticleFetchRequest(articleKind, fileLocation)
    }

    val request = mkRequest(file)

    Await.result(articleFetcher.fetch(request).map(_.get), articleFetchDuration)
  }

}

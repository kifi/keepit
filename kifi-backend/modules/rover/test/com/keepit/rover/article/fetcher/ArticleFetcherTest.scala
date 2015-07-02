package com.keepit.rover.article.fetcher

import com.keepit.rover.article.{ Article, ArticleKind }
import com.keepit.rover.test.RoverTestInjector

import scala.concurrent.{ ExecutionContext, Await }
import scala.concurrent.duration._

trait ArticleFetcherTest[A <: Article, FetcherType <: ArticleFetcher[A]] extends RoverTestInjector {

  val fetcherClass: Class[FetcherType]

  val articleKind: ArticleKind[A]
  val articleFetchDuration = 10.seconds

  lazy val articleFetcher: FetcherType =
    withInjector(FileHttpFetcherModule()) { implicit injector =>
      injector.getInstance(fetcherClass)
    }

  def fetch(file: String, url: String = "")(implicit ec: ExecutionContext): A = {

    val request = {
      val actualUrl = if (url == "") file else url
      val fileLocation = s"test/com/keepit/rover/article/fetcher/fixtures/$file:::$actualUrl"
      ArticleFetchRequest(articleKind, fileLocation)
    }

    Await.result(articleFetcher.fetch(request).map(_.get), articleFetchDuration)
  }

}

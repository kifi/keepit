package com.keepit.rover.article.fetcher

import com.google.inject.Injector
import com.keepit.rover.article.{ Article, ArticleKind }
import com.keepit.rover.test.RoverTestInjector
import org.specs2.matcher.NoConcurrentExecutionContext
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions

import scala.concurrent.Await
import scala.concurrent.duration._

abstract class ArticleFetcherTest[A <: Article: ArticleKind, FetcherType <: ArticleFetcher[A]: Manifest]
    extends Specification with RoverTestInjector with NoTimeConversions with NoConcurrentExecutionContext {

  val articleKind = implicitly[ArticleKind[A]]

  val articleFetchDuration = 10.seconds

  def fetch(url: String, file: String)(implicit injector: Injector): A = {

    val articleFetcher = inject[FetcherType]

    val request = {
      val fileLocation = FileFetcherFormat(url, "test/com/keepit/rover/article/fetcher/fixtures/" + file)
      ArticleFetchRequest(articleKind, fileLocation)
    }

    Await.result(articleFetcher.fetch(request).map(_.get), articleFetchDuration)
  }

}

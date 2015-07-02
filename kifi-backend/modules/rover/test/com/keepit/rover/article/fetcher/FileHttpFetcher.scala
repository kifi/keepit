package com.keepit.rover.article.fetcher

import java.io.{ FileInputStream, File }

import com.google.inject.{ Provides, Singleton }
import com.keepit.rover.article.{ ArticleKind, Article }
import com.keepit.rover.fetcher._

import scala.concurrent.{ Future, ExecutionContext }

class FileHttpFetcher extends HttpFetcher {

  override def fetch[A](request: FetchRequest)(f: (FetchResult[HttpInputStream]) => A)(implicit ec: ExecutionContext): Future[A] =
    request.url match {
      case FileFetcherFormat(file, url) =>
        val stream = new HttpInputStream(new FileInputStream(file))
        val context = FetchContext.ok(url)
        val result = FetchResult(context, Some(stream))
        Future.successful(f(result))
    }

}

case class FileHttpFetcherModule() extends HttpFetcherModule {

  def configure(): Unit = {
    bind[HttpFetcher].to[FileHttpFetcher]
  }

  @Singleton
  @Provides
  def httpFetcher(): FileHttpFetcher = {
    new FileHttpFetcher
  }

}

object FileFetcherFormat {

  val urlRegex = """^([^:]*):::(.*)$""".r

  def apply(file: String, url: String): String = s"$file:::$url"

  def unapply(location: String): Option[(String, String)] = {
    location match {
      case urlRegex(file, url) => Some(file, url)
      case _ => None
    }
  }

}

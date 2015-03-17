package com.keepit.rover.fetcher

import java.io.{ FilterInputStream, InputStream }
import java.nio.charset.Charset

import com.keepit.model.HttpProxy
import com.keepit.rover.article.HttpInfo
import org.joda.time.DateTime
import com.keepit.common.core._

import scala.concurrent.Future

case class FetchRequest(
  url: String,
  proxy: Option[HttpProxy] = None,
  ifModifiedSince: Option[DateTime] = None)

case class FetchResult[T](context: FetchContext, content: Option[T]) {
  def collect[U](validStatusCodes: Int*)(f: T => U): FetchResult[U] = {
    val collectedContent = content.collect {
      case input if validStatusCodes.contains(context.response.statusCode) => f(input)
    }
    copy(content = collectedContent)
  }
  def map[U](f: T => U): FetchResult[U] = {
    val mappedContent = content.map(f)
    copy(content = mappedContent)
  }
  def flatMap[U](f: T => Future[U]): Future[FetchResult[U]] = {
    val futureMappedContent = content match {
      case None => Future.successful(None)
      case Some(actualContent) => f(actualContent).imap(Some(_))
    }
    futureMappedContent.imap(mappedContent => copy(content = mappedContent))
  }
}

case class FetchRequestInfo(destinationUrl: String, redirects: Seq[HttpRedirect])
case class FetchResponseInfo(statusCode: Int, status: String, contentType: Option[String], contentCharset: Option[Charset])
case class FetchContext(request: FetchRequestInfo, response: FetchResponseInfo)

object FetchContext {
  def ok(destinationUrl: String): FetchContext = {
    FetchContext(
      FetchRequestInfo(destinationUrl, Seq()),
      FetchResponseInfo(200, "OK", None, None)
    )
  }

  implicit def toHttpInfo(context: FetchContext) = {
    HttpInfo(
      context.response.statusCode,
      Some(context.response.status).filter(_.nonEmpty),
      context.request.redirects,
      context.response.contentType,
      context.response.contentCharset.map(_.displayName())
    )
  }
}

class HttpInputStream(input: InputStream) extends FilterInputStream(input)

trait HttpFetcher {
  def fetch[A](request: FetchRequest)(f: FetchResult[HttpInputStream] => A): Future[A]
}

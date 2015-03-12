package com.keepit.rover.fetcher

import java.io.{ FilterInputStream, InputStream }

import com.keepit.model.HttpProxy
import org.joda.time.DateTime

import scala.concurrent.Future

case class FetchRequest(
  url: String,
  proxy: Option[HttpProxy] = None,
  ifModifiedSince: Option[DateTime] = None)

sealed trait FetchResult[A]
case class Fetched[A](context: FetchContext, content: A) extends FetchResult[A] with FetchContextHolder
case class NotModified[A](context: FetchContext) extends FetchResult[A] with FetchContextHolder
case class FetchHttpError[A](context: FetchContext) extends FetchResult[A] with FetchContextHolder
case class FetchContentExtractionError[A](context: FetchContext, cause: Throwable) extends FetchResult[A] with FetchContextHolder

object FetchedResult {
  implicit def toOption[A](result: FetchResult[A]): Option[A] = result match {
    case Fetched(_, content) => Some(content)
    case _ => None
  }
}

case class FetchRequestInfo(destinationUrl: String, redirects: Seq[HttpRedirect])
case class FetchResponseInfo(statusCode: Int, status: String, contentType: Option[String])
case class FetchContext(request: FetchRequestInfo, response: FetchResponseInfo)
object FetchContext {
  def ok(destinationUrl: String): FetchContext = {
    FetchContext(
      FetchRequestInfo(destinationUrl, Seq()),
      FetchResponseInfo(200, "OK", None)
    )
  }
}

trait FetchContextHolder { self: FetchResult[_] =>
  def context: FetchContext
}

class HttpInputStream(input: InputStream, val context: FetchContext) extends FilterInputStream(input)

trait HttpFetcher {
  def fetch[A](request: FetchRequest)(f: HttpInputStream => A): Future[FetchResult[A]]
}

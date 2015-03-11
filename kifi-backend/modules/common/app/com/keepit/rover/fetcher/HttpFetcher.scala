package com.keepit.rover.fetcher

import java.io.{ FilterInputStream, InputStream }

import com.keepit.model.HttpProxy
import org.joda.time.DateTime

import scala.concurrent.Future

case class FetchRequest(
  url: String,
  proxy: Option[HttpProxy] = None,
  ifModifiedSince: Option[DateTime] = None)

case class FetchResult[A](context: FetchContext, response: FetchResponse[A])

case class FetchContext(destinationUrl: String, redirects: Seq[HttpRedirect])

sealed trait FetchResponse[A]
case class Fetched[A](fetched: A) extends FetchResponse[A]
case class NotModified[A]() extends FetchResponse[A]
case class FetchHttpError[A](errorCode: Int, errorStatus: String) extends FetchResponse[A]
case class FetchContentExtractionError[A](cause: Throwable) extends FetchResponse[A]

object FetchedResult {
  implicit def toOption[A](content: FetchResponse[A]): Option[A] = content match {
    case Fetched(fetched) => Some(fetched)
    case NotModified() => None
    case FetchHttpError(_, _) => None
    case FetchContentExtractionError(_) => None
  }
}

class HttpInputStream(input: InputStream, val contentType: Option[String] = None) extends FilterInputStream(input)

trait HttpFetcher {
  def fetch[A](request: FetchRequest)(f: HttpInputStream => A): Future[FetchResult[A]]
}

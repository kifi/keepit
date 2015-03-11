package com.keepit.rover.fetcher

import java.io.{ FilterInputStream, InputStream }

import com.keepit.model.HttpProxy
import org.joda.time.DateTime

import scala.concurrent.Future
import scala.util.Try

case class FetchRequest(
  url: String,
  proxy: Option[HttpProxy] = None,
  ifModifiedSince: Option[DateTime] = None)

sealed trait FetchResponse[A] {
  def status: Int
  def statusText: Option[String]
}

case class FetchHttpContext(destinationUrl: String, redirects: Seq[HttpRedirect])

case class SuccessfulFetch[A](
  status: Int,
  statusText: Option[String],
  context: Try[FetchHttpContext],
  content: Try[A]) extends FetchResponse[A]

case class FailedFetch[A](status: Int, statusText: Option[String], exception: Option[Throwable]) extends FetchResponse[A]

class HttpInputStream(input: InputStream, val contentType: Option[String] = None) extends FilterInputStream(input)

trait HttpFetcher {
  def fetch[A](request: FetchRequest)(f: HttpInputStream => A): Future[FetchResponse[A]]
}


package com.keepit.rover.fetcher

import java.io.{ FilterInputStream, InputStream }

import com.keepit.model.HttpProxy
import com.keepit.rover.fetcher.HttpRedirect
import org.joda.time.DateTime

import scala.concurrent.Future
import scala.util.Try

case class FetchRequest(
  url: String,
  proxy: Option[HttpProxy] = None,
  ifModifiedSince: Option[DateTime] = None)

case class FetchResponse[A](
  statusCode: Int,
  message: Option[String],
  destinationUrl: String,
  redirects: Seq[HttpRedirect],
  content: Try[A])

class HttpInputStream(input: InputStream, val contentType: String) extends FilterInputStream(input)

trait HttpFetcher {
  def fetch[A](request: FetchRequest)(f: HttpInputStream => A): Future[FetchResponse[A]]
}


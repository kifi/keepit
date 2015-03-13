package com.keepit.rover.fetcher

import java.io.{ FilterInputStream, InputStream }

import com.keepit.model.HttpProxy
import org.joda.time.DateTime

import scala.concurrent.Future

case class FetchRequest(
  url: String,
  proxy: Option[HttpProxy] = None,
  ifModifiedSince: Option[DateTime] = None)

case class FetchResult(context: FetchContext, content: Option[HttpInputStream])

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

class HttpInputStream(input: InputStream) extends FilterInputStream(input)

trait HttpFetcher {
  def fetch[A](request: FetchRequest)(f: FetchResult => A): Future[A]
}

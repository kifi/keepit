package com.keepit.scraper.fetcher

import java.io.EOFException
import java.net.{ SocketTimeoutException, SocketException }
import java.util.zip.ZipException

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.logging.Logging
import com.keepit.rover.fetcher.apache.ApacheHttpFetcher
import com.keepit.rover.fetcher._
import org.apache.http.{ ConnectionClosedException, HttpStatus }

import scala.concurrent.Future

case class DeprecatedHttpFetchStatus(statusCode: Int, message: Option[String], context: Option[FetchContext]) {
  def destinationUrl: Option[String] = context.map(_.destinationUrl)
  def redirects: Seq[HttpRedirect] = context.map(_.redirects) getOrElse Seq.empty
}

object DeprecatedHttpFetchStatus {
  implicit def fromFetchResult(result: FetchResult[Unit]): DeprecatedHttpFetchStatus = {
    val (statusCode, message) = result.response match {
      case Fetched(_) => (HttpStatus.SC_OK, None)
      case NotModified() => (HttpStatus.SC_NOT_MODIFIED, None)
      case FetchHttpError(code, status) => (code, Some(status))
      case FetchContentExtractionError(cause) => (HttpStatus.SC_OK, Some(cause.getMessage))
    }
    DeprecatedHttpFetchStatus(statusCode, message, Some(result.context))
  }

}

object DeprecatedHttpFetcher {
  val NO_OP = { is: HttpInputStream => }
}

trait DeprecatedHttpFetcher {
  def fetch(request: FetchRequest)(f: HttpInputStream => Unit): DeprecatedHttpFetchStatus
  def get(request: FetchRequest)(f: HttpInputStream => Unit): Future[DeprecatedHttpFetchStatus]
}

@Singleton
class DeprecatedHttpFetcherImpl @Inject() (apacheHttpFetcher: ApacheHttpFetcher) extends DeprecatedHttpFetcher with Logging {
  def fetch(request: FetchRequest)(f: HttpInputStream => Unit): DeprecatedHttpFetchStatus = {
    apacheHttpFetcher.doFetch(request)(f).map(DeprecatedHttpFetchStatus.fromFetchResult).recover(toInternalServerError(request))get
  }
  def get(request: FetchRequest)(f: HttpInputStream => Unit): Future[DeprecatedHttpFetchStatus] = {
    implicit val ec = ExecutionContext.immediate
    apacheHttpFetcher.fetch(request)(f).map(DeprecatedHttpFetchStatus.fromFetchResult).recover(toInternalServerError(request))
  }

  private def toInternalServerError(request: FetchRequest): PartialFunction[Throwable, DeprecatedHttpFetchStatus] = {
    {
      case eof: EOFException =>
        val msg = getErrorMessage(request, eof)
        log.warn(msg, eof)
        DeprecatedHttpFetchStatus(statusCode = 500, message = Some(msg), context = None)
      case ste: SocketException =>
        val msg = getErrorMessage(request, ste)
        log.warn(msg, ste)
        DeprecatedHttpFetchStatus(statusCode = 500, message = Some(msg), context = None)
      case ste: SocketTimeoutException =>
        val msg = getErrorMessage(request, ste)
        log.warn(msg, ste)
        DeprecatedHttpFetchStatus(statusCode = 500, message = Some(msg), context = None)
      case cce: ConnectionClosedException =>
        val msg = getErrorMessage(request, cce)
        log.warn(msg, cce)
        DeprecatedHttpFetchStatus(statusCode = 500, message = Some(msg), context = None)
      case ze: ZipException =>
        val msg = getErrorMessage(request, ze)
        log.warn(msg, ze)
        DeprecatedHttpFetchStatus(statusCode = 500, message = Some(msg), context = None)
    }
  }

  private def getErrorMessage(request: FetchRequest, t: Throwable): String = {
    s"${t.getClass.getSimpleName} on fetching url [${request.url}] if modified since [${request.ifModifiedSince}] using proxy [${request.proxy}]"
  }
}

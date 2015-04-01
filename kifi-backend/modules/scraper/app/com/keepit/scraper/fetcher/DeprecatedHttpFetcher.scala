package com.keepit.scraper.fetcher

import java.io.EOFException
import java.net.{ SocketTimeoutException, SocketException }
import java.util.zip.ZipException

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.concurrent.ExecutionContext.{ immediate, fj }
import com.keepit.common.logging.Logging
import com.keepit.rover.fetcher.apache.ApacheHttpFetcher
import com.keepit.rover.fetcher._
import org.apache.http.{ ConnectionClosedException, HttpStatus }
import scala.concurrent.Future

case class DeprecatedHttpFetchStatus(statusCode: Int, message: Option[String], context: Option[FetchContext]) {
  def destinationUrl: Option[String] = context.map(_.request.destinationUrl)
  def redirects: Seq[HttpRedirect] = context.map(_.request.redirects) getOrElse Seq.empty
}

object DeprecatedHttpFetcher {
  val NO_OP = { is: FetchResult[HttpInputStream] => }
}

trait DeprecatedHttpFetcher {
  def fetch(request: FetchRequest)(f: FetchResult[HttpInputStream] => Unit): DeprecatedHttpFetchStatus
  def get(request: FetchRequest)(f: FetchResult[HttpInputStream] => Unit): Future[DeprecatedHttpFetchStatus]
}

@Singleton
class DeprecatedHttpFetcherImpl @Inject() (apacheHttpFetcher: ApacheHttpFetcher) extends DeprecatedHttpFetcher with Logging {
  def fetch(request: FetchRequest)(f: FetchResult[HttpInputStream] => Unit): DeprecatedHttpFetchStatus = {
    apacheHttpFetcher.doFetch(request)(processResultWith(f)).recover(toInternalServerError(request)).get
  }
  def get(request: FetchRequest)(f: FetchResult[HttpInputStream] => Unit): Future[DeprecatedHttpFetchStatus] = {
    apacheHttpFetcher.fetch(request)(processResultWith(f))(fj).recover(toInternalServerError(request))(immediate)
  }

  private def processResultWith(f: FetchResult[HttpInputStream] => Unit)(result: FetchResult[HttpInputStream]): DeprecatedHttpFetchStatus = {
    result.context.response.statusCode match {
      case HttpStatus.SC_OK => {
        f(result)
        DeprecatedHttpFetchStatus(HttpStatus.SC_OK, None, Some(result.context))
      }
      case HttpStatus.SC_NOT_MODIFIED => DeprecatedHttpFetchStatus(HttpStatus.SC_NOT_MODIFIED, None, Some(result.context))
      case errorCode => DeprecatedHttpFetchStatus(errorCode, Some(result.context.response.status), Some(result.context))

    }
  }

  private def toInternalServerError(request: FetchRequest): PartialFunction[Throwable, DeprecatedHttpFetchStatus] = {
    {
      case eof: EOFException =>
        val msg = getErrorMessage(request, eof)
        log.warn(msg, eof)
        DeprecatedHttpFetchStatus(statusCode = HttpStatus.SC_INTERNAL_SERVER_ERROR, message = Some(msg), context = None)
      case ste: SocketException =>
        val msg = getErrorMessage(request, ste)
        log.warn(msg, ste)
        DeprecatedHttpFetchStatus(statusCode = HttpStatus.SC_INTERNAL_SERVER_ERROR, message = Some(msg), context = None)
      case ste: SocketTimeoutException =>
        val msg = getErrorMessage(request, ste)
        log.warn(msg, ste)
        DeprecatedHttpFetchStatus(statusCode = HttpStatus.SC_INTERNAL_SERVER_ERROR, message = Some(msg), context = None)
      case cce: ConnectionClosedException =>
        val msg = getErrorMessage(request, cce)
        log.warn(msg, cce)
        DeprecatedHttpFetchStatus(statusCode = HttpStatus.SC_INTERNAL_SERVER_ERROR, message = Some(msg), context = None)
      case ze: ZipException =>
        val msg = getErrorMessage(request, ze)
        log.warn(msg, ze)
        DeprecatedHttpFetchStatus(statusCode = HttpStatus.SC_INTERNAL_SERVER_ERROR, message = Some(msg), context = None)
      case InvalidFetchRequestException(_, cause) =>
        val msg = getErrorMessage(request, cause)
        log.warn(msg, cause)
        DeprecatedHttpFetchStatus(statusCode = HttpStatus.SC_BAD_REQUEST, message = Some(msg), context = None)
    }
  }

  private def getErrorMessage(request: FetchRequest, t: Throwable): String = {
    s"${t.getClass.getSimpleName} on fetching url [${request.url}] if modified since [${request.ifModifiedSince}] using proxy [${request.proxy}]"
  }
}

package com.keepit.rover.fetcher.apache

import java.util.zip.ZipException

import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.rover.fetcher._

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.performance._
import com.keepit.common.plugin.SchedulingProperties
import org.apache.http.HttpHeaders._
import org.apache.http._
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.config.RegistryBuilder
import org.apache.http.conn.socket.{ ConnectionSocketFactory, PlainConnectionSocketFactory }
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.http.util.EntityUtils
import com.keepit.common.core._

import scala.concurrent.Future
import scala.ref.WeakReference
import scala.util.{ Failure, Success, Try }

case class ApacheHttpFetcherException(message: String, cause: Option[Throwable] = None) extends Throwable(message) {
  cause.foreach(initCause)
}

// based on Apache HTTP Client (this one is blocking but feature-rich & flexible; see http://hc.apache.org/httpcomponents-client-ga/index.html)
class ApacheHttpFetcher(val airbrake: AirbrakeNotifier, userAgent: String, connectionTimeout: Int, soTimeOut: Int, schedulingProperties: SchedulingProperties, scraperHttpConfig: HttpFetchEnforcerConfig) extends HttpFetcher with Logging {

  private val cm = {
    val registry = RegistryBuilder.create[ConnectionSocketFactory]
    registry.register("http", PlainConnectionSocketFactory.INSTANCE)
    registry.register("https", UnsafeSSLSocketFactory())
    new PoolingHttpClientConnectionManager(registry.build())
  }
  cm.setMaxTotal(100)

  private val defaultRequestConfig = RequestConfig.custom().setConnectTimeout(connectionTimeout).setSocketTimeout(soTimeOut).build()
  private val httpClient = {
    val httpClientBuilder = HttpClientBuilder.create()
    httpClientBuilder.setDefaultRequestConfig(defaultRequestConfig)
    httpClientBuilder.setConnectionManager(cm)
    httpClientBuilder.setUserAgent(userAgent)
    httpClientBuilder.setRequestExecutor(new ContentAwareHttpRequestExecutor())
    httpClientBuilder.addInterceptorFirst(new RedirectInterceptor()) // track redirects
    httpClientBuilder.addInterceptorFirst(new EncodingInterceptor()) // transfer encoding
    httpClientBuilder.build()
  }

  private val q = HttpFetchEnforcer.makeQueue(schedulingProperties, scraperHttpConfig, airbrake)

  def fetch[A](request: FetchRequest)(f: HttpInputStream => A): Future[FetchResult[A]] = SafeFuture {
    doFetch(request)(f).get
  }(ExecutionContext.fj)

  def doFetch[A](request: FetchRequest)(f: HttpInputStream => A): Try[FetchResult[A]] = {
    timing(s"ApacheHttpFetcher.doFetch(${request.url} ${request.proxy.map { p => s" via ${p.alias}" }.getOrElse("")}") {
      execute(request).map {
        case (apacheRequest, closeableResponse) =>
          val fetchResponse = processResponse(apacheRequest, closeableResponse, f)
          FetchResult(apacheRequest.context, fetchResponse)
      }
    } recoverWith {
      case ex: Throwable =>
        val msg = s"${ex.getClass.getSimpleName} on fetching url [${request.url}}] if modified since [${request.ifModifiedSince}] using proxy [${request.proxy}]"
        log.error(msg, ex)
        Failure(ex)
    }
  }

  private def execute(request: FetchRequest, disableGzip: Boolean = false): Try[(ApacheFetchRequest, CloseableHttpResponse)] = {
    buildApacheFetchRequest(request, disableGzip) match {
      case Failure(ex) => {
        val message = s"Failed to build Apache request from $request"
        Failure(ApacheHttpFetcherException(message, Some(ex)))
      }
      case Success(apacheRequest) => {
        apacheRequest.execute().map((apacheRequest, _)) recoverWith {
          case e: ZipException if (!disableGzip) => execute(request, disableGzip = true) // Retry with gzip compression disabled
          case ex: Throwable => {
            val failed = if (apacheRequest.isAborted) "has been ABORTED" else "has FAILED"
            val message = s"fetch request ($request) $failed ($apacheRequest)"
            Failure(ApacheHttpFetcherException(message, Some(ex)))
          }
        }
      }
    }
  }

  private def buildApacheFetchRequest(request: FetchRequest, disableGzip: Boolean): Try[ApacheFetchRequest] = {
    Try {
      ApacheFetchRequest(httpClient, airbrake)(request, defaultRequestConfig, disableGzip) tap {
        apacheRequest => q.offer(WeakReference(apacheRequest))
      }
    }
  }

  private def processResponse[A](apacheRequest: ApacheFetchRequest, response: CloseableHttpResponse, f: HttpInputStream => A): FetchResponse[A] = {
    try {
      getFetchResponse(response, f)
    } catch {
      case ex: Throwable =>
        apacheRequest.abort()
        throw ex
    } finally {
      EntityUtils.consumeQuietly(response.getEntity)
      response.close()
    }
  }

  private def getFetchResponse[A](response: CloseableHttpResponse, f: HttpInputStream => A): FetchResponse[A] = {
    response.getStatusLine.getStatusCode match {
      case HttpStatus.SC_OK => extractContent(response, f) match {
        case Success(content) => Fetched(content)
        case Failure(error) => FetchContentExtractionError(error)
      }
      case HttpStatus.SC_NOT_MODIFIED => NotModified()
      case errorCode => FetchHttpError(errorCode, response.getStatusLine.toString)
    }
  }

  private def extractContent[A](response: CloseableHttpResponse, f: HttpInputStream => A): Try[A] = Try {
    val entity = response.getEntity
    val input = {
      val contentType = Option(response.getLastHeader(CONTENT_TYPE)).map(_.getValue)
      val content = entity.getContent
      new HttpInputStream(content, contentType)
    }
    try {
      f(input)
    } finally {
      input.close()
    }
  }
}

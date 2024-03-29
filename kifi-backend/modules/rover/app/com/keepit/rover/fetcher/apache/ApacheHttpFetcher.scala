package com.keepit.rover.fetcher.apache

import java.net.SocketTimeoutException
import java.util.zip.ZipException

import com.keepit.rover.fetcher._

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.performance._
import com.keepit.common.plugin.SchedulingProperties
import org.apache.http.client.config.RequestConfig
import org.apache.http.config.RegistryBuilder
import org.apache.http.conn.socket.{ ConnectionSocketFactory, PlainConnectionSocketFactory }
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }
import scala.ref.WeakReference
import scala.util.{ Failure, Try }

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
    httpClientBuilder.addInterceptorLast(new RequestSecurityInterceptor())
    httpClientBuilder.addInterceptorFirst(new ResponseSecurityInterceptor()) // blocks localhost/private scrapes
    httpClientBuilder.addInterceptorFirst(new RedirectInterceptor()) // track redirects
    httpClientBuilder.addInterceptorFirst(new EncodingInterceptor()) // transfer encoding
    httpClientBuilder.build()
  }

  private val q = HttpFetchEnforcer.makeQueue(schedulingProperties, scraperHttpConfig, airbrake)

  def fetch[A](request: FetchRequest)(f: FetchResult[HttpInputStream] => A)(implicit ec: ExecutionContext): Future[A] = {
    // not using SafeFuture here, alerting logic in Try
    Future { doFetch(request)(f).get }
  }

  def doFetch[A](request: FetchRequest)(f: FetchResult[HttpInputStream] => A): Try[A] = {
    timing(s"ApacheHttpFetcher.doFetch(${request.url} ${request.proxy.map { p => s" via ${p.alias}" }.getOrElse("")}") {
      execute(request).map {
        case (apacheRequest, apacheResponse) =>
          processResponse(request, apacheRequest, apacheResponse, f)
      }
    } recoverWith {
      case ex: Throwable if Option(ex.getClass.getSimpleName).exists(_ == "FetchThrottlingException") => // Skip logging this one
        Failure(ex)
      case ex: Throwable =>
        val proxy = s" using proxy [${request.proxy}]"
        val msg = s"${ex.getClass.getSimpleName} on fetching url [${request.url}}] if modified since [${request.ifModifiedSince}]$proxy. Cause: ${ex.getMessage}"
        log.error(msg)
        Failure(ex)
    }
  }

  private def execute(request: FetchRequest, disableGzip: Boolean = false, retryOnTimeout: Boolean = true): Try[(ApacheFetchRequest, ApacheFetchResponse)] = {
    buildApacheFetchRequest(request, disableGzip).flatMap { apacheRequest =>
      apacheRequest.execute().map((apacheRequest, _))
    } recoverWith {
      case z: ZipException if !disableGzip => execute(request, disableGzip = true) // Retry with gzip compression disabled
      case s: SocketTimeoutException if retryOnTimeout => execute(request, disableGzip, retryOnTimeout = false)
      case t: Throwable => Failure(InvalidFetchRequestException(request, t))
    }
  }

  private def buildApacheFetchRequest(request: FetchRequest, disableGzip: Boolean): Try[ApacheFetchRequest] = {
    Try {
      ApacheFetchRequest(httpClient, airbrake)(request, defaultRequestConfig, disableGzip) tap {
        apacheRequest => q.offer(WeakReference(apacheRequest))
      }
    }
  }

  private def processResponse[A](originalRequest: FetchRequest, apacheRequest: ApacheFetchRequest, response: ApacheFetchResponse, f: FetchResult[HttpInputStream] => A): A = {
    try {
      doProcessResponse(apacheRequest.info(originalRequest.proxy), response, f)
    } catch {
      case ex: Throwable =>
        apacheRequest.abort()
        throw ex
    } finally {
      response.close()
    }
  }

  private def doProcessResponse[A](requestInfo: FetchRequestInfo, response: ApacheFetchResponse, f: FetchResult[HttpInputStream] => A): A = {
    val context = FetchContext(requestInfo, response.info)
    val input = response.content.map(new HttpInputStream(_))
    val result = FetchResult(context, input)
    try {
      f(result)
    } finally {
      input.map(_.close())
    }
  }
}

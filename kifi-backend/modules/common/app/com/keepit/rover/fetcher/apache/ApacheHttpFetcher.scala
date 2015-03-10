package com.keepit.rover.fetcher.apache

import java.io.{ EOFException, IOException }
import java.net._
import java.security.GeneralSecurityException
import java.util.concurrent.{ ConcurrentLinkedQueue, Executors, ThreadFactory, TimeUnit }
import java.util.zip.ZipException

import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.strings._
import com.keepit.rover.fetcher._

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.performance._
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.scraper._
import org.apache.http.HttpHeaders._
import org.apache.http._
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.{ CloseableHttpResponse, HttpGet }
import org.apache.http.config.RegistryBuilder
import org.apache.http.conn.socket.{ ConnectionSocketFactory, PlainConnectionSocketFactory }
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.http.protocol.HttpContext
import org.apache.http.util.EntityUtils

import scala.concurrent.Future
import scala.ref.WeakReference
import scala.util.{ Failure, Success, Try }
import scala.concurrent.duration._

import org.apache.commons.io.IOUtils

import com.keepit.rover.fetcher.{ DeprecatedFetcherHttpContext, HttpRedirect }

// based on Apache HTTP Client (this one is blocking but feature-rich & flexible; see http://hc.apache.org/httpcomponents-client-ga/index.html)
class ApacheHttpFetcher(val airbrake: AirbrakeNotifier, userAgent: String, connectionTimeout: Int, soTimeOut: Int, schedulingProperties: SchedulingProperties, scraperHttpConfig: ScraperHttpConfig) extends DeprecatedHttpFetcher with Logging with ScraperUtils {
  import ApacheHttpFetcher._

  val cm = {
    val registry = RegistryBuilder.create[ConnectionSocketFactory]
    registry.register("http", PlainConnectionSocketFactory.INSTANCE)
    registry.register("https", UnsafeSSLSocketFactory())
    new PoolingHttpClientConnectionManager(registry.build())
  }
  cm.setMaxTotal(100)

  val defaultRequestConfig = RequestConfig.custom().setConnectTimeout(connectionTimeout).setSocketTimeout(soTimeOut).build()
  val httpClient = {
    val httpClientBuilder = HttpClientBuilder.create()
    httpClientBuilder.setDefaultRequestConfig(defaultRequestConfig)
    httpClientBuilder.setConnectionManager(cm)
    httpClientBuilder.setUserAgent(userAgent)
    httpClientBuilder.setRequestExecutor(new ContentAwareHttpRequestExecutor())
    httpClientBuilder.addInterceptorFirst(new RedirectInterceptor()) // track redirects
    httpClientBuilder.addInterceptorFirst(new EncodingInterceptor()) // transfer encoding
    httpClientBuilder.build()
  }

  private def logAndSet[T](fetchInfo: FetchExecutionInfo, ret: T)(t: Throwable, tag: String, ctx: String, notify: Boolean = false): T = {
    logErr(t, tag, ctx, notify)
    fetchInfo.exRef.set(t)
    ret
  }

  val q = new ConcurrentLinkedQueue[WeakReference[FetchExecutionInfo]]()
  if (schedulingProperties.enabled) {
    val enforcer = {
      val Q_SIZE_THRESHOLD = scraperHttpConfig.httpFetcherQSizeThreshold
      new HttpFetchEnforcer(q, Q_SIZE_THRESHOLD, airbrake)
    }

    val scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory {
      def newThread(r: Runnable): Thread = {
        val thread = new Thread(r, "HttpFetcher-Enforcer")
        log.debug(s"[HttpFetcher] $thread created")
        thread
      }
    })

    val ENFORCER_FREQ: Int = scraperHttpConfig.httpFetcherEnforcerFreq
    scheduler.scheduleWithFixedDelay(enforcer, ENFORCER_FREQ, ENFORCER_FREQ, TimeUnit.SECONDS)
  }

  private case class HttpFetchHandlerResult(responseOpt: Option[CloseableHttpResponse], fetchInfo: FetchExecutionInfo, httpGet: HttpGet, httpContext: HttpContext)
  private object HttpFetchHandlerResult {
    implicit def reponseOpt2HttpFetchHandlerResult(responseOpt: Option[CloseableHttpResponse])(implicit fetchInfo: FetchExecutionInfo, httpGet: HttpGet, httpContext: HttpContext): HttpFetchHandlerResult =
      HttpFetchHandlerResult(responseOpt, fetchInfo, httpGet, httpContext)
  }

  private def fetchHandler(request: FetchRequest, disableGzip: Boolean = false): HttpFetchHandlerResult = {
    implicit val ApacheFetchRequest(httpGet, httpContext) = ApacheFetchRequest(request, defaultRequestConfig, disableGzip)
    val url = httpGet.getURI.toString
    implicit val fetchInfo = FetchExecutionInfo(url, System.currentTimeMillis, httpGet, Thread.currentThread()) // pass this up
    try {
      q.offer(WeakReference(fetchInfo))
      val response = timingWithResult[CloseableHttpResponse](s"fetch($url).execute", { r: CloseableHttpResponse => r.getStatusLine.toString }) {
        println(s"[scrape-start] $url")
        System.out.flush()
        httpClient.execute(httpGet, httpContext)
      }
      fetchInfo.respStatusRef.set(response.getStatusLine)
      Some(response)
    } catch {
      case e: ZipException => if (disableGzip) logAndSet(fetchInfo, None)(e, "fetch", url, true)

      else fetchHandler(request, true) // Retry with gzip compression disabled
      case e @ (_: IOException | _: GeneralSecurityException) => logAndSet(fetchInfo, None)(e, "fetch", url)
      case e: NullPointerException => logAndSet(fetchInfo, None)(e, "fetch", url) //can happen on BrowserCompatSpec.formatCookies
      case t: Throwable => logAndSet(fetchInfo, None)(t, "fetch", url, true)
    } finally {
      println(s"[scrape-end] $url")
    }
  }

  def fetch(request: FetchRequest)(f: HttpInputStream => Unit): DeprecatedHttpFetchStatus = timing(s"HttpFetcher.fetch(${request.url} ${request.proxy.map { p => s" via ${p.alias}" }.getOrElse("")}") {
    val HttpFetchHandlerResult(responseOpt, fetchInfo, httpGet, httpContext) = fetchHandler(request)
    responseOpt match {
      case None =>
        DeprecatedHttpFetchStatus(HttpStatus.SC_BAD_REQUEST, Some(s"fetch request ($request) FAILED to execute ($fetchInfo)"), httpContext)
      case Some(response) if httpGet.isAborted =>
        DeprecatedHttpFetchStatus(HttpStatus.SC_BAD_REQUEST, Some(s"fetch request ($request) has been ABORTED ($fetchInfo)"), httpContext)
      case Some(response) => {
        val statusCode = response.getStatusLine.getStatusCode
        val entity = response.getEntity

        // If the response does not enclose an entity, there is no need to bother about connection release
        if (entity != null) {
          try {
            val httpInputStream = Try {
              val contentType = Option(response.getHeaders(CONTENT_TYPE)).flatMap(_.lastOption.map(_.getValue))
              new HttpInputStream(entity.getContent, contentType)
            }
            httpInputStream match {
              case Success(input) =>
                try {
                  consumeInput(statusCode, input, httpContext, request.url, response, httpGet, entity, f)
                } finally {
                  Try(input.close())
                }
              case Failure(error) =>
                log.error(s"error getting content for $request", error)
                DeprecatedHttpFetchStatus(-1, Some(error.toString), httpContext)
            }
          } finally {
            Try(EntityUtils.consumeQuietly(entity))
          }
        } else {
          httpGet.abort()
          statusCode match {
            case HttpStatus.SC_OK =>
              log.info(s"request failed, no entity found: [${response.getStatusLine().toString()}][$request]")
              DeprecatedHttpFetchStatus(-1, Some("no entity found"), httpContext)
            case HttpStatus.SC_NOT_MODIFIED =>
              DeprecatedHttpFetchStatus(statusCode, None, httpContext)
            case _ =>
              val content = Option(entity) match {
                case Some(e) => IOUtils.toString(new HttpInputStream(e.getContent, None), UTF8).abbreviate(1000)
                case None => "null content entity"
              }
              log.info(s"request failed while parsing response, bad error code: [${response.getStatusLine().toString()}][$request] with content: $content")
              DeprecatedHttpFetchStatus(statusCode, Some(s"${response.getStatusLine.toString} : $content"), httpContext)
          }
        }
      }
    }
  }

  private def consumeInput(statusCode: Int, input: HttpInputStream, httpContext: HttpContext, url: String,
    response: CloseableHttpResponse, httpGet: HttpGet, entity: HttpEntity, f: HttpInputStream => Unit): DeprecatedHttpFetchStatus = {
    try {
      statusCode match {
        case HttpStatus.SC_OK =>
          f(input)
          DeprecatedHttpFetchStatus(statusCode, None, httpContext)
        case HttpStatus.SC_NOT_MODIFIED =>
          DeprecatedHttpFetchStatus(statusCode, None, httpContext)
        case _ =>
          val content = IOUtils.toString(input, UTF8).abbreviate(1000)
          log.info(s"request failed while consuming data, bad error code: [${response.getStatusLine().toString()}][$url] with content: $content")
          DeprecatedHttpFetchStatus(statusCode, Some(s"${response.getStatusLine.toString} : $content"), httpContext)
      }
    } catch {
      case ex: IOException =>
        // in case of an IOException the connection will be released back to the connection manager automatically
        throw ex
      case ex: Exception =>
        // unexpected exception. abort the request in order to shut down the underlying connection immediately.
        httpGet.abort()
        throw ex
    }
  }

  private lazy val consolidateFetch = new RequestConsolidator[FetchRequest, DeprecatedHttpFetchStatus](5 minutes)

  def get(request: FetchRequest)(f: (HttpInputStream) => Unit): Future[DeprecatedHttpFetchStatus] = consolidateFetch(request) {
    case FetchRequest(url, ifModifiedSince, proxy) =>
      SafeFuture {
        try {
          fetch(request)(f)
        } catch {
          case eof: EOFException =>
            val msg = s"EOF on fetching url [$url] if modified since [$ifModifiedSince] using proxy [$proxy]"
            log.warn(msg, eof)
            DeprecatedHttpFetchStatus(statusCode = 500, message = Some(msg), context = new DeprecatedFetcherHttpContext() { def redirects = Seq[HttpRedirect](); def destinationUrl = None })
          case ste: SocketException =>
            val msg = s"SocketException on fetching url [$url] if modified since [$ifModifiedSince] using proxy [$proxy]"
            log.warn(msg, ste)
            DeprecatedHttpFetchStatus(statusCode = 500, message = Some(msg), context = new DeprecatedFetcherHttpContext() { def redirects = Seq[HttpRedirect](); def destinationUrl = None })
          case ste: SocketTimeoutException =>
            val msg = s"SocketTimeoutException on fetching url [$url] if modified since [$ifModifiedSince] using proxy [$proxy]"
            log.warn(msg, ste)
            DeprecatedHttpFetchStatus(statusCode = 500, message = Some(msg), context = new DeprecatedFetcherHttpContext() { def redirects = Seq[HttpRedirect](); def destinationUrl = None })
          case cce: ConnectionClosedException =>
            val msg = s"ConnectionClosedException on fetching url [$url] if modified since [$ifModifiedSince] using proxy [$proxy]"
            log.warn(msg, cce)
            DeprecatedHttpFetchStatus(statusCode = 500, message = Some(msg), context = new DeprecatedFetcherHttpContext() { def redirects = Seq[HttpRedirect](); def destinationUrl = None })
          case ze: ZipException =>
            val msg = s"ZipException on fetching url [$url] if modified since [$ifModifiedSince] using proxy [$proxy]"
            log.warn(msg, ze)
            DeprecatedHttpFetchStatus(statusCode = 500, message = Some(msg), context = new DeprecatedFetcherHttpContext() { def redirects = Seq[HttpRedirect](); def destinationUrl = None })
          case e: Exception =>
            throw new Exception(s"on fetching url [$url] if modified since [$ifModifiedSince] using proxy [$proxy]", e)
        }
      }(ExecutionContext.fj)
  }
}

object ApacheHttpFetcher {
  import RedirectInterceptor._
  implicit class DeprecatedFetcherHttpContextAdaptor(context: HttpContext) extends DeprecatedFetcherHttpContext {
    def destinationUrl: Option[String] = Option(context.getAttribute(scraperDestinationUrlAttribute).asInstanceOf[String])
    def redirects: Seq[HttpRedirect] = Option(context.getAttribute(redirectsAttribute).asInstanceOf[Seq[HttpRedirect]]).getOrElse(Seq.empty[HttpRedirect])
  }
}

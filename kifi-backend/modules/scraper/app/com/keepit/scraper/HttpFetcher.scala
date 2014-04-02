package com.keepit.scraper

import org.joda.time.DateTime
import com.keepit.model.HttpProxy
import org.apache.http.protocol.{BasicHttpContext, HttpContext}
import org.apache.http._
import com.keepit.common.logging.Logging
import org.apache.http.config.RegistryBuilder
import org.apache.http.conn.socket.{PlainConnectionSocketFactory, ConnectionSocketFactory}
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.{BasicCredentialsProvider, HttpClientBuilder}
import org.apache.http.HttpHeaders._
import org.apache.http.client.entity.{DeflateDecompressingEntity, GzipDecompressingEntity}
import org.apache.http.client.methods.{CloseableHttpResponse, HttpGet}
import org.apache.http.auth.{UsernamePasswordCredentials, AuthScope}
import org.apache.http.client.protocol.HttpClientContext
import java.io.{EOFException, IOException}
import scala.util.Try
import org.apache.http.util.EntityUtils
import com.keepit.common.time._
import com.keepit.common.performance.{timing, timingWithResult}
import java.util.concurrent.{ThreadFactory, TimeUnit, Executors, ConcurrentLinkedQueue}
import scala.ref.WeakReference
import play.api.Play
import Play.current
import com.keepit.common.healthcheck.AirbrakeNotifier
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.net._
import org.apache.http.conn.{HttpHostConnectException, ConnectTimeoutException}
import org.apache.http.client.ClientProtocolException
import javax.net.ssl.{SSLException, SSLHandshakeException}
import HttpStatus._
import java.util.zip.ZipException
import java.security.cert.CertPathBuilderException
import sun.security.validator.ValidatorException
import com.keepit.common.plugin.SchedulingProperties

import com.keepit.common.net.URI

trait HttpFetcher {
  val NO_OP = {is:HttpInputStream => }
  def fetch(url: String, ifModifiedSince: Option[DateTime] = None, proxy: Option[HttpProxy] = None)(f: HttpInputStream => Unit): HttpFetchStatus
  def close()
}

class HttpFetcherImpl(val airbrake:AirbrakeNotifier, userAgent: String, connectionTimeout: Int, soTimeOut: Int, trustBlindly: Boolean, schedulingProperties:SchedulingProperties, scraperHttpConfig: ScraperHttpConfig) extends HttpFetcher with Logging with ScraperUtils {
  val cm = if (trustBlindly) {
    val registry = RegistryBuilder.create[ConnectionSocketFactory]
    registry.register("http", PlainConnectionSocketFactory.INSTANCE)
    registry.register("https", UnsafeSSLSocketFactory())
    new PoolingHttpClientConnectionManager(registry.build())
  } else {
    new PoolingHttpClientConnectionManager
  }
  cm.setMaxTotal(100)

  val defaultRequestConfig = RequestConfig.custom().setConnectTimeout(connectionTimeout).setSocketTimeout(soTimeOut).build()

  private val httpClientBuilder = HttpClientBuilder.create()
  httpClientBuilder.setDefaultRequestConfig(defaultRequestConfig)
  httpClientBuilder.setConnectionManager(cm)
  httpClientBuilder.setUserAgent(userAgent)

  // track redirects
  val redirectInterceptor = new HttpResponseInterceptor() {
    override def process(response: HttpResponse, context: HttpContext) {
      if (response.containsHeader(LOCATION)) {
        val locations = response.getHeaders(LOCATION)
        if (locations.length > 0) {
          val currentLocation = context.getAttribute("scraper_destination_url").asInstanceOf[String]
          val redirect = HttpRedirect.withStandardizationEffort(response.getStatusLine.getStatusCode, currentLocation, locations(0).getValue())
          val redirects = context.getAttribute("redirects").asInstanceOf[Seq[HttpRedirect]] :+ redirect
          context.setAttribute("redirects", redirects)
          context.setAttribute("scraper_destination_url", redirect.newDestination)
        }
      }
    }
  }
  httpClientBuilder.addInterceptorFirst(redirectInterceptor)

  // transfer encoding
  val encodingInterceptor = new HttpResponseInterceptor() {
    override def process(response: HttpResponse, context: HttpContext) {
      val entity = response.getEntity()
      if (entity != null) {
        val ceheader = entity.getContentEncoding()
        if (ceheader != null) {
          val codecs = ceheader.getElements()
          codecs.foreach{ codec =>
            if (codec.getName().equalsIgnoreCase("gzip")) {
              response.setEntity(new GzipDecompressingEntity(response.getEntity()))
              return
            }
            if (codec.getName().equalsIgnoreCase("deflate")) {
              response.setEntity(new DeflateDecompressingEntity(response.getEntity()))
              return
            }
          }
          val encoding = codecs.map(_.getName).mkString(",")
          log.error(s"unsupported content-encoding: ${encoding}")
        }
      }
    }
  }
  httpClientBuilder.addInterceptorFirst(encodingInterceptor)

  val httpClient = httpClientBuilder.build()

  val LONG_RUNNING_THRESHOLD = if (Play.maybeApplication.isDefined && Play.isDev) 1000 else sys.props.get("fetcher.abort.threshold") map (_.toInt) getOrElse (2 * 1000 * 60) // Play reference can be removed
  val Q_SIZE_THRESHOLD = scraperHttpConfig.httpFetcherQSizeThreshold

  case class FetchInfo(url:String, ts:Long, htpGet:HttpGet, thread:Thread) {
    val killCount = new AtomicInteger()
    val respStatusRef = new AtomicReference[StatusLine]()
    val exRef = new AtomicReference[Throwable]()
    override def toString = s"[Fetch($url,${ts},${thread.getName})] isAborted=${htpGet.isAborted} killCount=${killCount} respRef=${respStatusRef} exRef=${exRef}"
  }

  private def removeRef(iter:java.util.Iterator[_], msgOpt:Option[String] = None) {
    try {
      for (msg <- msgOpt)
        log.info(msg)
      iter.remove()
    } catch {
      case t:Throwable =>
        log.error(s"[terminator] Caught exception $t; (cause=${t.getCause}) while attempting to remove entry from queue")
    }
  }

  private def logAndSet[T](fetchInfo:FetchInfo, ret:T)(t:Throwable, tag:String, ctx:String, notify:Boolean = false):T = {
    logErr(t, tag, ctx, notify)
    fetchInfo.exRef.set(t)
    ret
  }

  val q = new ConcurrentLinkedQueue[WeakReference[FetchInfo]]()
  val enforcer = new Runnable {
    def run():Unit = {
      try {
        log.info(s"[enforcer] checking for long running fetch requests ... q.size=${q.size}")
        if (!q.isEmpty) {
          val iter = q.iterator
          while (iter.hasNext) {
            val curr = System.currentTimeMillis
            val ref = iter.next
            ref.get map { case ft:FetchInfo =>
              if (ft.respStatusRef.get != null) {
                val sc = ft.respStatusRef.get.getStatusCode
                removeRef(iter, if (sc != SC_OK && sc != SC_NOT_MODIFIED) Some(s"[enforcer] ${ft.url} finished with abnormal status:${ft.respStatusRef.get}") else None)
              } else if (ft.exRef.get != null) removeRef(iter, Some(s"[enforcer] ${ft.url} caught error ${ft.exRef.get}; remove from q"))
              else if (ft.htpGet.isAborted) removeRef(iter, Some(s"[enforcer] ${ft.url} is aborted; remove from q"))
              else {
                val runMillis = curr - ft.ts
                if (runMillis > LONG_RUNNING_THRESHOLD * 2) {
                  val msg = s"[enforcer] attempt# ${ft.killCount.get} to abort long ($runMillis ms) fetch task: ${ft.htpGet.getURI}"
                  log.warn(msg)
                  ft.htpGet.abort() // inform scraper
                  ft.killCount.incrementAndGet()
                  log.info(s"[enforcer] ${ft.htpGet.getURI} isAborted=${ft.htpGet.isAborted}")
                  if (!ft.htpGet.isAborted) {
                    log.warn(s"[enforcer] failed to abort long ($runMillis ms) fetch task $ft; calling interrupt ...")
                    ft.thread.interrupt
                    if (ft.thread.isInterrupted) {
                      log.warn(s"[enforcer] thread ${ft.thread} has been interrupted for fetch task $ft")
                      // removeRef -- maybe later
                    } else {
                      val msg = s"[enforcer] attempt# ${ft.killCount.get} failed to interrupt ${ft.thread} for fetch task $ft"
                      log.error(msg)
                      if (ft.killCount.get % 5 == 0)
                        airbrake.notify(msg)
                    }
                  }
                } else if (runMillis > LONG_RUNNING_THRESHOLD) {
                  log.warn(s"[enforcer] potential long ($runMillis ms) running task: $ft; stackTrace=${ft.thread.getStackTrace.mkString("|")}")
                } else {
                  log.info(s"[enforcer] $ft has been running for $runMillis ms")
                }
              }
            } orElse {
              removeRef(iter)
              None
            }
          }
        }
        if (q.size > Q_SIZE_THRESHOLD) {
          airbrake.notify(s"[enforcer] q.size (${q.size}) crossed threshold ($Q_SIZE_THRESHOLD)")
        } else if (q.size > Q_SIZE_THRESHOLD/2) {
          log.warn(s"[enforcer] q.size (${q.size}) crossed threshold/2 ($Q_SIZE_THRESHOLD)")
        }
      } catch {
        case t:Throwable =>
          airbrake.notify(s"[enforcer] Caught exception $t; queue=$q; cause=${t.getCause}; stack=${t.getStackTraceString}")
      }
    }
  }
  val scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory {
    def newThread(r: Runnable): Thread = {
      val thread = new Thread(r, "HttpFetcher-Enforcer")
      log.info(s"[HttpFetcher] $thread created")
      thread
    }
  })
  val ENFORCER_FREQ: Int = scraperHttpConfig.httpFetcherEnforcerFreq
  if (schedulingProperties.enabled) {
    scheduler.scheduleWithFixedDelay(enforcer, ENFORCER_FREQ, ENFORCER_FREQ, TimeUnit.SECONDS)
  }

  private case class HttpFetchHandlerResult(responseOpt: Option[CloseableHttpResponse], fetchInfo: FetchInfo, httpGet: HttpGet, httpContext: HttpContext)
  private object HttpFetchHandlerResult {
    implicit def reponseOpt2HttpFetchHandlerResult(responseOpt: Option[CloseableHttpResponse])(implicit fetchInfo: FetchInfo, httpGet: HttpGet, httpContext: HttpContext): HttpFetchHandlerResult =
      HttpFetchHandlerResult(responseOpt, fetchInfo, httpGet, httpContext)
  }

  private def fetchHandler(url: String, ifModifiedSince: Option[DateTime] = None, proxy: Option[HttpProxy] = None, disableGzip: Boolean = false): HttpFetchHandlerResult = {
    if (URI.parse(url).get.host.isEmpty) throw new IllegalArgumentException(s"url $url has no host!")
    implicit val httpGet = new HttpGet(url)
    implicit val httpContext = new BasicHttpContext()

    proxy.map { httpProxy =>
      val requestConfigWithProxy = RequestConfig.copy(defaultRequestConfig).setProxy(new HttpHost(httpProxy.hostname, httpProxy.port, httpProxy.scheme)).build()
      httpGet.setConfig(requestConfigWithProxy)
      for {
        user <- httpProxy.username
        password <- httpProxy.password
      } yield {
        val credentials = new BasicCredentialsProvider()
        credentials.setCredentials(new AuthScope(httpProxy.hostname, httpProxy.port), new UsernamePasswordCredentials(user, password))
        httpContext.setAttribute(HttpClientContext.CREDS_PROVIDER, credentials)
      }
    }

    ifModifiedSince.foreach{ ifModifiedSince =>
      httpGet.addHeader(IF_MODIFIED_SINCE, ifModifiedSince.toHttpHeaderString)
    }

    httpContext.setAttribute("scraper_destination_url", url)
    httpContext.setAttribute("redirects", Seq.empty[HttpRedirect])

    if (disableGzip) httpGet.addHeader(ACCEPT_ENCODING, "")
    implicit val fetchInfo = FetchInfo(url, System.currentTimeMillis, httpGet, Thread.currentThread()) // pass this up
    try {
      q.offer(WeakReference(fetchInfo))
      val response = timingWithResult[CloseableHttpResponse](s"fetch($url).execute", { r:CloseableHttpResponse => r.getStatusLine.toString }) { httpClient.execute(httpGet, httpContext) }
      fetchInfo.respStatusRef.set(response.getStatusLine)
      Some(response)
    } catch {
        case e:ZipException => if (disableGzip) logAndSet(fetchInfo, None)(e, "fetch", url, true)
                               else fetchHandler(url, ifModifiedSince, proxy, true) // Retry with gzip compression disabled
        case e:ConnectException => logAndSet(fetchInfo, None)(e, "fetch", url)
        case e:ValidatorException => logAndSet(fetchInfo, None)(e, "fetch", url)
        case e:CertPathBuilderException => logAndSet(fetchInfo, None)(e, "fetch", url)
        case e:SSLException => logAndSet(fetchInfo, None)(e, "fetch", url)
        case e:SSLHandshakeException => logAndSet(fetchInfo, None)(e, "fetch", url)
        case e:NoHttpResponseException => logAndSet(fetchInfo, None)(e, "fetch", url)
        case e:EOFException => logAndSet(fetchInfo, None)(e, "fetch", url)
        case e:HttpHostConnectException => logAndSet(fetchInfo, None)(e, "fetch", url)
        case e:ClientProtocolException => logAndSet(fetchInfo, None)(e, "fetch", url)
        case e:NoRouteToHostException => logAndSet(fetchInfo, None)(e, "fetch", url)
        case e:UnknownHostException => logAndSet(fetchInfo, None)(e, "fetch", url)
        case e:ConnectTimeoutException => logAndSet(fetchInfo, None)(e, "fetch", url)
        case e:SocketException => logAndSet(fetchInfo, None)(e, "fetch", url)
        case e:SocketTimeoutException => logAndSet(fetchInfo, None)(e, "fetch", url)
        case t:Throwable => logAndSet(fetchInfo, None)(t, "fetch", url, true)
    }
  }

  def fetch(url: String, ifModifiedSince: Option[DateTime] = None, proxy: Option[HttpProxy] = None)(f: HttpInputStream => Unit): HttpFetchStatus = timing(s"HttpFetcher.fetch($url) ${proxy.map{p => s" via ${p.alias}"}.getOrElse("")}") {
    val HttpFetchHandlerResult(responseOpt, fetchInfo, httpGet, httpContext) = fetchHandler(url, ifModifiedSince, proxy)
    responseOpt match {
      case None =>
        HttpFetchStatus(HttpStatus.SC_BAD_REQUEST, Some(s"fetch request ($url) FAILED to execute ($fetchInfo)"), httpContext)
      case Some(response) if httpGet.isAborted =>
        HttpFetchStatus(HttpStatus.SC_BAD_REQUEST, Some(s"fetch request ($url) has been ABORTED ($fetchInfo)"), httpContext)
      case Some(response) => {
        val statusCode = response.getStatusLine.getStatusCode
        val entity = response.getEntity

        // If the response does not enclose an entity, there is no need to bother about connection release
        if (entity != null) {

          val input = new HttpInputStream(entity.getContent)

          Option(response.getHeaders(CONTENT_TYPE)).foreach{ headers =>
            if (headers.length > 0) input.setContentType(headers(headers.length - 1).getValue())
          }

          try {
            statusCode match {
              case HttpStatus.SC_OK =>
                f(input)
                HttpFetchStatus(statusCode, None, httpContext)
              case HttpStatus.SC_NOT_MODIFIED =>
                HttpFetchStatus(statusCode, None, httpContext)
              case _ =>
                log.info("request failed: [%s][%s]".format(response.getStatusLine().toString(), url))
                HttpFetchStatus(statusCode, Some(response.getStatusLine.toString), httpContext)
            }
          } catch {
            case ex: IOException =>
              // in case of an IOException the connection will be released back to the connection manager automatically
              throw ex
            case ex :Exception =>
              // unexpected exception. abort the request in order to shut down the underlying connection immediately.
              httpGet.abort()
              throw ex
          } finally {
            Try(EntityUtils.consumeQuietly(entity))
            Try(input.close())
          }
        } else {
          httpGet.abort()
          statusCode match {
            case HttpStatus.SC_OK =>
              log.info("request failed: [%s][%s]".format(response.getStatusLine().toString(), url))
              HttpFetchStatus(-1, Some("no entity found"), httpContext)
            case HttpStatus.SC_NOT_MODIFIED =>
              HttpFetchStatus(statusCode, None, httpContext)
            case _ =>
              log.info("request failed: [%s][%s]".format(response.getStatusLine().toString(), url))
              HttpFetchStatus(statusCode, Some(response.getStatusLine.toString), httpContext)
          }
        }
      }
    }
  }

  def close() {
    httpClient.close()
  }
}
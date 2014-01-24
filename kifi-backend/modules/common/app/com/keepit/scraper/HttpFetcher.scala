package com.keepit.scraper

import org.joda.time.DateTime
import com.keepit.model.HttpProxy
import org.apache.http.protocol.{BasicHttpContext, HttpContext}
import org.apache.http._
import com.keepit.common.net.URI
import com.keepit.common.logging.Logging
import org.apache.http.config.RegistryBuilder
import org.apache.http.conn.socket.{PlainConnectionSocketFactory, ConnectionSocketFactory}
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.{BasicCredentialsProvider, HttpClientBuilder}
import org.apache.http.HttpHeaders._
import org.apache.http.client.entity.{DeflateDecompressingEntity, GzipDecompressingEntity}
import org.apache.http.client.methods.HttpGet
import org.apache.http.auth.{UsernamePasswordCredentials, AuthScope}
import org.apache.http.client.protocol.HttpClientContext
import java.io.IOException
import scala.util.Try
import org.apache.http.util.EntityUtils
import com.keepit.common.time._
import com.keepit.common.performance.timing
import java.util.concurrent.{ThreadFactory, TimeUnit, Executors, ConcurrentLinkedQueue}
import scala.ref.WeakReference
import play.api.{Logger, Play}
import Play.current
import com.keepit.common.healthcheck.AirbrakeNotifier
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.net.{SocketException, NoRouteToHostException, UnknownHostException, SocketTimeoutException}
import org.apache.http.conn.{HttpHostConnectException, ConnectTimeoutException}
import org.apache.http.client.ClientProtocolException
import javax.net.ssl.{SSLException, SSLHandshakeException}
import HttpStatus._

trait HttpFetcher {
  def fetch(url: String, ifModifiedSince: Option[DateTime] = None, proxy: Option[HttpProxy] = None)(f: HttpInputStream => Unit): HttpFetchStatus
  def close()
}

case class HttpFetchStatus(statusCode: Int, message: Option[String], context: HttpContext) {
  def destinationUrl = Option(context.getAttribute("scraper_destination_url").asInstanceOf[String])
  def redirects = Option(context.getAttribute("redirects").asInstanceOf[Seq[HttpRedirect]]).getOrElse(Seq.empty[HttpRedirect])
}

case class HttpRedirect(statusCode: Int, currentLocation: String, newDestination: String) {
  def isPermanent = (statusCode == HttpStatus.SC_MOVED_PERMANENTLY)
  def isAbsolute = URI.isAbsolute(currentLocation) && URI.isAbsolute(newDestination)
  def isLocatedAt(url: String) = (currentLocation == url)
}

object HttpRedirect {
  def withStandardizationEffort(statusCode: Int, currentLocation: String, newDestination: String): HttpRedirect = HttpRedirect(statusCode, currentLocation, URI.url(currentLocation, newDestination))

  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  implicit val format = (
    (__ \ 'statusCode).format[Int] and
    (__ \ 'currentLocation).format[String] and
    (__ \ 'newDestination).format[String]
  )(HttpRedirect.apply _, unlift(HttpRedirect.unapply))
}


class HttpFetcherImpl(val airbrake:AirbrakeNotifier, userAgent: String, connectionTimeout: Int, soTimeOut: Int, trustBlindly: Boolean) extends HttpFetcher with Logging with ScraperUtils {
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

  val LONG_RUNNING_THRESHOLD = if (Play.maybeApplication.isDefined && Play.isDev) 1000 else sys.props.get("fetcher.abort.threshold") map (_.toInt) getOrElse (5 * 1000 * 60) // Play reference can be removed
  val Q_SIZE_THRESHOLD = sys.props.get("fetcher.queue.size.threshold") map (_.toInt) getOrElse (100)

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
        if (q.isEmpty) {
          // log.info(s"[enforcer] queue is empty")
        } else {
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
                    log.warn(s"[enforcer] failed to abort long ($runMillis ms) fetch task ${ft}; calling interrupt ...")
                    ft.thread.interrupt
                    if (ft.thread.isInterrupted) {
                      log.warn(s"[enforcer] thread ${ft.thread} has been interrupted for fetch task ${ft}")
                      // removeRef -- maybe later
                    } else {
                      val msg = s"[enforcer] attempt# ${ft.killCount.get} failed to interrupt ${ft.thread} for fetch task ${ft}"
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
  scheduler.scheduleWithFixedDelay(enforcer, 30, 10, TimeUnit.SECONDS)

  def fetch(url: String, ifModifiedSince: Option[DateTime] = None, proxy: Option[HttpProxy] = None)(f: HttpInputStream => Unit): HttpFetchStatus = timing(s"HttpFetcher.fetch: url=$url,proxy=$proxy") {
    val ts = System.currentTimeMillis
    val httpGet = new HttpGet(url)
    val httpContext = new BasicHttpContext()

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

    log.info(s"[fetch($url)] executing request " + httpGet.getURI() + proxy.map(httpProxy => s" via ${httpProxy.alias}").getOrElse(""))

    httpContext.setAttribute("scraper_destination_url", url)
    httpContext.setAttribute("redirects", Seq.empty[HttpRedirect])

    val fetchInfo = FetchInfo(url, System.currentTimeMillis, httpGet, Thread.currentThread()) // pass this up
    val responseOpt = try {
      q.offer(WeakReference(fetchInfo))
      val response = httpClient.execute(httpGet, httpContext)
      fetchInfo.respStatusRef.set(response.getStatusLine)
      log.info(s"[fetch($url)] time-lapsed:${System.currentTimeMillis - ts} response status:${response.getStatusLine.toString}")
      Some(response)
    } catch {
      case e:SSLException               => logAndSet(fetchInfo, None)(e, "fetch", url)
      case e:java.io.EOFException       => logAndSet(fetchInfo, None)(e, "fetch", url)
      case e:SSLHandshakeException      => logAndSet(fetchInfo, None)(e, "fetch", url)
      case e:HttpHostConnectException   => logAndSet(fetchInfo, None)(e, "fetch", url)
      case e:ClientProtocolException    => logAndSet(fetchInfo, None)(e, "fetch", url)
      case e:NoRouteToHostException     => logAndSet(fetchInfo, None)(e, "fetch", url)
      case e:UnknownHostException       => logAndSet(fetchInfo, None)(e, "fetch", url)
      case e:ConnectTimeoutException    => logAndSet(fetchInfo, None)(e, "fetch", url)
      case e:SocketException            => logAndSet(fetchInfo, None)(e, "fetch", url)
      case e:SocketTimeoutException     => logAndSet(fetchInfo, None)(e, "fetch", url)
      case t:Throwable                  => logAndSet(fetchInfo, None)(t, "fetch", url, true)
    }

    responseOpt match {
      case None =>
        HttpFetchStatus(HttpStatus.SC_BAD_REQUEST, Some(s"fetch request ($url) FAILED to execute ($fetchInfo)"), httpContext)
      case Some(response) if (httpGet.isAborted) =>
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
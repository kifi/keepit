package com.keepit.scraper

import org.joda.time.DateTime
import com.keepit.model.HttpProxy
import org.apache.http.protocol.{BasicHttpContext, HttpContext}
import org.apache.http.{HttpHost, HttpResponse, HttpResponseInterceptor, HttpStatus}
import com.keepit.common.net.URI
import com.keepit.common.logging.Logging
import org.apache.http.config.RegistryBuilder
import org.apache.http.conn.socket.{PlainConnectionSocketFactory, ConnectionSocketFactory}
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.{BasicCredentialsProvider, HttpClientBuilder}
import org.apache.http.HttpHeaders._
import scala.Some
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
import play.api.Play
import Play.current
import collection.JavaConversions._
import scala.concurrent.duration.Duration
import com.keepit.common.healthcheck.AirbrakeNotifier

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


class HttpFetcherImpl(airbrake:AirbrakeNotifier, userAgent: String, connectionTimeout: Int, soTimeOut: Int, trustBlindly: Boolean) extends HttpFetcher with Logging {
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

  val LONG_RUNNING_THRESHOLD = if (Play.isDev) 200 else sys.props.get("fetcher.abort.threshold") map (_.toInt) getOrElse (5 * 1000 * 60)

  val q = new ConcurrentLinkedQueue[WeakReference[(Long, HttpGet)]]()
  val enforcer = new Runnable {
    def run():Unit = {
      log.info(s"[enforcer] checking for long running fetch requests ... ${q.size}")
      if (q.isEmpty) {
        log.info(s"[enforcer] queue is empty")
      } else {
        val iter = q.iterator
        while (iter.hasNext) {
          val curr = System.currentTimeMillis
          val ref = iter.next
          ref.get map { case (ts, htpGet) =>
            log.info(s"[enforcer] ${htpGet.getURI} has been running for ${curr - ts} ms")
            if (curr - ts > LONG_RUNNING_THRESHOLD) {
              val msg = s"[enforcer] ABORT long (${curr - ts} ms) fetch task: ${htpGet.getURI}"
              log.warn(msg)
              htpGet.abort()
              log.info(s"[enforcer] ${htpGet.getURI} isAborted=${htpGet.isAborted}")
              if (!htpGet.isAborted)
                airbrake.notify(s"Failed to abort long (${curr - ts} ms) fetch task ${htpGet.getURI}")
            }
          } orElse {
            log.info(s"[enforcer] remove collected entry $ref from queue")
            try { iter.remove() } catch {
              case t:Throwable =>
                log.error(s"[enforcer] Caught exception $t; (cause=${t.getCause}) while attempting to remove collected entry $ref from queue; stack=${t.getStackTraceString}")
            }
            None
          }
        }
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
  scheduler.scheduleWithFixedDelay(enforcer, 5, 5, TimeUnit.SECONDS)

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

    log.info("[fetch] executing request " + httpGet.getURI() + proxy.map(httpProxy => s" via ${httpProxy.alias}").getOrElse(""))

    httpContext.setAttribute("scraper_destination_url", url)
    httpContext.setAttribute("redirects", Seq.empty[HttpRedirect])

    q.offer(WeakReference((System.currentTimeMillis, httpGet)))
    val response = httpClient.execute(httpGet, httpContext)
    log.info(s"[fetch] time-lapsed:${System.currentTimeMillis - ts} response status:${response.getStatusLine.toString}")

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

  def close() {
    httpClient.close()
  }
}
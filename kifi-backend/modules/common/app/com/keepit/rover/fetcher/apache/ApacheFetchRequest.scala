package com.keepit.rover.fetcher.apache

import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.{ AtomicLong, AtomicReference, AtomicInteger }

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ URISanitizer, URI }
import com.keepit.rover.fetcher.{ FetchContext, HttpRedirect, FetchRequest }
import org.apache.http.HttpHeaders._
import org.apache.http.{ StatusLine, HttpHost }
import org.apache.http.auth.{ UsernamePasswordCredentials, AuthScope }
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.{ CloseableHttpResponse, HttpGet }
import org.apache.http.client.protocol.HttpClientContext
import org.apache.http.impl.client.{ CloseableHttpClient, BasicCredentialsProvider }
import org.apache.http.protocol.{ BasicHttpContext, HttpContext }
import com.keepit.common.time._
import play.api.Logger
import com.keepit.common.core._
import com.keepit.common.performance._

import scala.util.{ Failure, Success, Try }

class ApacheFetchRequest(httpClient: CloseableHttpClient, airbrake: AirbrakeNotifier)(httpGet: HttpGet, httpContext: HttpContext) extends Logging {
  val killCount = new AtomicInteger()
  val respStatusRef = new AtomicReference[StatusLine]()
  val exRef = new AtomicReference[Throwable]()
  val thread = new AtomicReference[Thread]()
  val executedAt = new AtomicLong()

  override def toString = {
    val executionInfo = Option(thread.get).map { executionThread =>
      s"${executedAt.get},${executionThread.getName}"
    } getOrElse "Not executed"
    s"[Fetch($url,$executionInfo)] isAborted=${isAborted} killCount=${killCount} respRef=${respStatusRef} exRef=${exRef}"
  }

  def url: String = httpGet.getURI.toString
  def isAborted: Boolean = httpGet.isAborted
  def context: FetchContext = RedirectInterceptor.getFetchContext(httpContext).get

  def execute(): Try[CloseableHttpResponse] = {
    Try {
      executedAt.set(System.currentTimeMillis)
      thread.set(Thread.currentThread())
      timingWithResult[CloseableHttpResponse](s"fetch(${url}).execute", { r: CloseableHttpResponse => r.getStatusLine.toString }) {
        println(s"[fetch-start] ${url}")
        System.out.flush()
        httpClient.execute(httpGet, httpContext)
      }
    } tap { _ => println(s"[fetch-end] ${url}") } tap {
      case Success(httpResponse) => respStatusRef.set(httpResponse.getStatusLine)
      case Failure(e @ (_: IOException | _: GeneralSecurityException | _: NullPointerException)) => logAndSetError(e, notify = false) // NullPointerException can happen on BrowserCompatSpec.formatCookies
      case Failure(t) => logAndSetError(t, notify = true)
    }
  }

  def abort(): Unit = { httpGet.abort() }

  private def logAndSetError(t: Throwable, notify: Boolean = false): Unit = {
    logErr(t, "fetch", url, notify)
    exRef.set(t)
  }

  private def formatErr(t: Throwable, tag: String, ctx: String) = s"[$tag] ($ctx) Caught exception (${t}); cause:${t.getCause}"
  private def logErr(t: Throwable, tag: String, ctx: String, notify: Boolean = false)(implicit log: Logger): Unit = {
    val msg = formatErr(t, tag, ctx)
    log.error(msg, t)
    if (notify) airbrake.notify(msg, t)
  }
}

object ApacheFetchRequest {
  def apply(httpClient: CloseableHttpClient, airbrake: AirbrakeNotifier)(request: FetchRequest, defaultRequestConfig: RequestConfig, disableGzip: Boolean): ApacheFetchRequest = {
    val httpGet = {
      val url = {
        val uri = URI.parse(request.url).get
        if (uri.host.isEmpty) throw new IllegalArgumentException(s"url $uri has no host!")
        uri.toString()
      }
      try {
        new HttpGet(url)
      } catch {
        case _: Exception => {
          val sanitizedUrl = URISanitizer.sanitize(url) // try sanitized version of URL as a fallback
          new HttpGet(sanitizedUrl)
        }
      }
    }

    val httpContext = new BasicHttpContext()

    if (disableGzip) httpGet.addHeader(ACCEPT_ENCODING, "")

    request.proxy.map { httpProxy =>
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

    request.ifModifiedSince.foreach { ifModifiedSince =>
      httpGet.addHeader(IF_MODIFIED_SINCE, ifModifiedSince.toHttpHeaderString)
    }

    // making sure redirection attributes are always set
    RedirectInterceptor.setRedirectionAttributes(httpContext, request.url, Seq.empty[HttpRedirect])

    new ApacheFetchRequest(httpClient, airbrake)(httpGet, httpContext)
  }
}

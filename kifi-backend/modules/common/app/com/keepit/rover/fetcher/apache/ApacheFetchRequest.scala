package com.keepit.rover.fetcher.apache

import com.keepit.common.net.{ URISanitizer, URI }
import com.keepit.rover.fetcher.{ HttpRedirect, FetchRequest }
import com.keepit.rover.fetcher.apache.RedirectInterceptor._
import org.apache.http.HttpHeaders._
import org.apache.http.HttpHost
import org.apache.http.auth.{ UsernamePasswordCredentials, AuthScope }
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.protocol.HttpClientContext
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.protocol.{ BasicHttpContext, HttpContext }
import com.keepit.common.time._

case class ApacheFetchRequest(httpGet: HttpGet, httpContext: HttpContext)

object ApacheFetchRequest {
  def apply(request: FetchRequest, defaultRequestConfig: RequestConfig, disableGzip: Boolean): ApacheFetchRequest = {
    val httpGet = {
      val url = {
        val uri = URI.parse(request.url).get
        if (uri.host.isEmpty) throw new IllegalArgumentException(s"url $uri has no host!")
        uri.toString()
      }
      try { new HttpGet(url) } catch {
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

    httpContext.setAttribute(scraperDestinationUrlAttribute, request.url)
    httpContext.setAttribute(redirectsAttribute, Seq.empty[HttpRedirect])
    ApacheFetchRequest(httpGet, httpContext)
  }
}

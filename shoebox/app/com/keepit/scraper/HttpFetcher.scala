package com.keepit.scraper

import com.keepit.common.logging.Logging
import com.keepit.common.time._
import org.joda.time.DateTime
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.entity.GzipDecompressingEntity
import org.apache.http.client.params.ClientPNames
import org.apache.http.HttpHeaders.{CONTENT_TYPE, IF_MODIFIED_SINCE, LOCATION}
import org.apache.http._
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.impl.conn.PoolingClientConnectionManager
import org.apache.http.params.HttpParams
import org.apache.http.params.BasicHttpParams
import org.apache.http.params.HttpConnectionParams
import org.apache.http.params.HttpProtocolParams
import org.apache.http.protocol.BasicHttpContext
import org.apache.http.protocol.HttpContext
import org.apache.http.util.EntityUtils
import java.io.{InputStream, IOException}
import java.net.URL
import scala.util.Try
import org.apache.http.conn.params.ConnRoutePNames
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials

trait HttpFetcher {
  def fetch(url: String, ifModifiedSince: Option[DateTime] = None)(f: HttpInputStream => Unit): HttpFetchStatus
  def close()
}

class HttpFetcherImpl(userAgent: String, connectionTimeout: Int, soTimeOut: Int) extends HttpFetcher with Logging {
  val cm = new PoolingClientConnectionManager
  cm.setMaxTotal(100)

  val httpParams = new BasicHttpParams
  HttpConnectionParams.setConnectionTimeout(httpParams, connectionTimeout)
  HttpConnectionParams.setSoTimeout(httpParams, soTimeOut)
  HttpProtocolParams.setUserAgent(httpParams, userAgent)
  val httpClient = new DefaultHttpClient(cm, httpParams)

  val proxyCm = new PoolingClientConnectionManager
  proxyCm.setMaxTotal(100)

  // track redirects
  val interceptor = new HttpResponseInterceptor() {
    override def process(response: HttpResponse, context: HttpContext) {
      if (response.containsHeader(LOCATION)) {
        val locations = response.getHeaders(LOCATION)
        if (locations.length > 0) context.setAttribute("scraper_destination_url", locations(0).getValue())
      }
    }
  }
  httpClient.addResponseInterceptor(interceptor)

  def fetch(url: String, ifModifiedSince: Option[DateTime] = None)(f: HttpInputStream => Unit): HttpFetchStatus = {


    val httpGet = new HttpGet(url)

    ifModifiedSince.foreach{ ifModifiedSince =>
      httpGet.addHeader(IF_MODIFIED_SINCE, ifModifiedSince.format)
    }

    log.info("executing request " + httpGet.getURI())

    val httpContext = new BasicHttpContext()

    val response = httpClient.execute(httpGet, httpContext)
    log.info(response.getStatusLine.toString)

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
    httpClient.getConnectionManager().shutdown()
  }
}

case class HttpFetchStatus(statusCode: Int, message: Option[String], context: HttpContext) {
  def destinationUrl = Option(context.getAttribute("scraper_destination_url").asInstanceOf[String])
}

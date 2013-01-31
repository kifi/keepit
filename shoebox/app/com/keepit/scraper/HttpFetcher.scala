package com.keepit.scraper

import com.keepit.common.logging.Logging
import com.keepit.common.time._
import org.joda.time.DateTime
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.entity.GzipDecompressingEntity
import org.apache.http.client.params.ClientPNames
import org.apache.http.HttpHeaders.{CONTENT_TYPE, IF_MODIFIED_SINCE, LOCATION}
import org.apache.http.HttpRequest
import org.apache.http.HttpRequestInterceptor
import org.apache.http.HttpResponse
import org.apache.http.HttpResponseInterceptor
import org.apache.http.HttpStatus
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.impl.conn.PoolingClientConnectionManager
import org.apache.http.params.HttpParams
import org.apache.http.params.BasicHttpParams
import org.apache.http.params.HttpConnectionParams
import org.apache.http.params.HttpProtocolParams
import org.apache.http.protocol.BasicHttpContext
import org.apache.http.protocol.HttpContext
import org.apache.http.util.EntityUtils
import java.io.InputStream
import java.io.IOException
import java.net.URL

class HttpFetcher extends Logging {
  val userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_2) AppleWebKit/537.17 (KHTML, like Gecko) Chrome/24.0.1309.0 Safari/537.17"
  val cm = new PoolingClientConnectionManager
  cm.setMaxTotal(100);

  val httpParams = new BasicHttpParams
  HttpConnectionParams.setConnectionTimeout(httpParams, 30000);
  HttpConnectionParams.setSoTimeout(httpParams, 30000);
  HttpProtocolParams.setUserAgent(httpParams, userAgent)
  val httpClient = new DefaultHttpClient(cm, httpParams)

  // track redirects
  httpClient.addResponseInterceptor(new HttpResponseInterceptor() {
    override def process(response: HttpResponse, context: HttpContext) {
      if (response.containsHeader(LOCATION)) {
        val locations = response.getHeaders(LOCATION)
        if (locations.length > 0) context.setAttribute("scraper_destination_url", locations(0).getValue())
      }
    }
  })

  def fetch(url: String, ifModifiedSince: Option[DateTime] = None)(f: HttpInputStream => Unit): HttpFetchStatus = {
    val httpGet = new HttpGet(url)

    ifModifiedSince.foreach{ ifModifiedSince =>
      httpGet.addHeader(IF_MODIFIED_SINCE, ifModifiedSince.format)
    }

    log.info("executing request " + httpGet.getURI())

    val httpContext = new BasicHttpContext()
    val response = httpClient.execute(httpGet, httpContext)
    log.info(response.getStatusLine);

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
          httpGet.abort();
          throw ex;
      } finally {
        try {
          EntityUtils.consumeQuietly(entity);
        } catch {
          case _ => // ignore any exception
        }
        try {
          input.close() // closing the input stream will trigger connection release
        } catch {
          case _ => // ignore any exception
        }
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
    // When HttpClient instance is no longer needed,
    // shut down the connection manager to ensure
    // immediate deallocation of all system resources
    httpClient.getConnectionManager().shutdown();
  }
}

case class HttpFetchStatus(statusCode: Int, message: Option[String], context: HttpContext) {
  def destinationUrl = Option(context.getAttribute("scraper_destination_url").asInstanceOf[String])
}

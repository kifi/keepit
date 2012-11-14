package com.keepit.scraper

import com.keepit.common.logging.Logging
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.entity.GzipDecompressingEntity
import org.apache.http.client.params.ClientPNames
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
  val httpclient = new DefaultHttpClient(cm, httpParams)
  
  def fetch(url: String)(f: HttpInputStream => Unit): HttpFetchStatus = {
    val httpget = new HttpGet(url)
    log.info("executing request " + httpget.getURI())
    
    val response = httpclient.execute(httpget)
    log.info(response.getStatusLine);
    
    val entity = response.getEntity
    
    // If the response does not enclose an entity, there is no need to bother about connection release
    if (entity != null) {
      
      
      val input = new HttpInputStream(entity.getContent)
      
      Option(response.getHeaders("Content-Type")).foreach{ headers =>
        if (headers.length > 0) input.setContentType(headers(headers.length - 1).getValue())
      }
      
      try {
        val statusCode = response.getStatusLine.getStatusCode
        statusCode match {
          case HttpStatus.SC_OK => 
            f(input)
            HttpFetchStatus(statusCode, None)
          case _ => 
            log.info("request failed: [%s][%s]".format(response.getStatusLine().toString(), url))
            HttpFetchStatus(statusCode, Some(response.getStatusLine.toString))
        }
      } catch {
        case ex: IOException =>
          // in case of an IOException the connection will be released back to the connection manager automatically
          throw ex
        case ex :Exception =>
          // unexpected exception. abort the request in order to shut down the underlying connection immediately.
          httpget.abort();
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
      httpget.abort();
      HttpFetchStatus(-1, Some("no entity found"))
    }
  }
  
  def close() {
    // When HttpClient instance is no longer needed,
    // shut down the connection manager to ensure
    // immediate deallocation of all system resources
    httpclient.getConnectionManager().shutdown();
  }
}

case class HttpFetchStatus(statusCode: Int, message: Option[String])

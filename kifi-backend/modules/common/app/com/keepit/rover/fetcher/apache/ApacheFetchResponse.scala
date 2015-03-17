package com.keepit.rover.fetcher.apache

import java.io.InputStream

import com.keepit.rover.fetcher.FetchResponseInfo
import org.apache.http.StatusLine
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.util.EntityUtils
import org.apache.http.entity.ContentType

class ApacheFetchResponse(response: CloseableHttpResponse) {
  def getStatusLine: StatusLine = response.getStatusLine

  def info: FetchResponseInfo = {
    val contentType = ContentType.getOrDefault(response.getEntity)
    FetchResponseInfo(
      getStatusLine.getStatusCode,
      getStatusLine.toString,
      Option(contentType.getMimeType),
      Option(contentType.getCharset)
    )
  }

  def content: Option[InputStream] = Option(response.getEntity).map(_.getContent)

  def close(): Unit = {
    EntityUtils.consumeQuietly(response.getEntity)
    response.close()
  }
}

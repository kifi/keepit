package com.keepit.rover.fetcher.apache

import java.io.InputStream

import com.keepit.rover.fetcher.FetchResponseInfo
import org.apache.http.HttpHeaders._
import org.apache.http.StatusLine
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.util.EntityUtils

class ApacheFetchResponse(response: CloseableHttpResponse) {
  def getStatusLine: StatusLine = response.getStatusLine

  def info: FetchResponseInfo = FetchResponseInfo(
    getStatusLine.getStatusCode,
    getStatusLine.toString,
    Option(response.getLastHeader(CONTENT_TYPE)).map(_.getValue)
  )

  def content: Option[InputStream] = Option(response.getEntity).map(_.getContent)

  def close(): Unit = {
    EntityUtils.consumeQuietly(response.getEntity)
    response.close()
  }
}

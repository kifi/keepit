package com.keepit.rover.fetcher.apache

import com.keepit.common.logging.Logging
import org.apache.http.client.entity.{ DeflateDecompressingEntity, GzipDecompressingEntity }
import org.apache.http.protocol.HttpContext
import org.apache.http.{ HttpResponse, HttpResponseInterceptor }

class EncodingInterceptor extends HttpResponseInterceptor with Logging {
  def process(response: HttpResponse, context: HttpContext) {
    val entity = response.getEntity()
    if (entity != null) {
      val ceheader = entity.getContentEncoding()
      if (ceheader != null) {
        val codecs = ceheader.getElements()
        codecs.foreach { codec =>
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

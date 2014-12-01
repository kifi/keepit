package com.keepit.commanders

import java.io.InputStream

import com.keepit.common.logging.Logging
import com.keepit.common.net.FakeWebService

import play.api.libs.iteratee.Enumerator
import play.api.libs.ws.WSResponseHeaders

class FakeProcessedImageHelper extends ProcessedImageHelper with Logging {
  val webService = new FakeWebService()

  private[commanders] def respondWith(contentType: Option[String], content: Array[Byte]): Unit = {
    webService.setGlobalStreamResponse { _ =>
      val headers = new WSResponseHeaders {
        override def status: Int = 200
        override def headers: Map[String, Seq[String]] = {
          contentType.map { ct =>
            Map("Content-Type" -> Seq(ct))
          } getOrElse Map.empty
        }
      }
      (headers, Enumerator(content))
    }
  }
}

package com.keepit.commanders

import com.keepit.common.logging.Logging
import com.keepit.common.net.{ WebService, FakeWebService }
import com.keepit.model.ImageFormat
import net.codingwell.scalaguice.ScalaModule
import play.api.libs.Files.TemporaryFile
import play.api.libs.iteratee.Enumerator
import play.api.libs.ws.WSResponseHeaders

import scala.collection.mutable
import scala.concurrent.Future

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

  val fakeRemoteImages = mutable.Map[String, (ImageFormat, TemporaryFile)]()
  protected override def fetchRemoteImage(imageUrl: String, timeoutMs: Int = 20000): Future[(ImageFormat, TemporaryFile)] = {
    fakeRemoteImages.get(imageUrl).map(Future.successful).getOrElse { super.fetchRemoteImage(imageUrl, timeoutMs) }
  }
}


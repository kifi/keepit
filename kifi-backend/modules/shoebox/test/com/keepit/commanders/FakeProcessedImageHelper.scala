package com.keepit.commanders

import java.io.{ FileInputStream, File }

import com.google.inject.Singleton
import com.keepit.common.logging.Logging
import com.keepit.common.net.FakeWebService
import net.codingwell.scalaguice.ScalaModule
import org.apache.commons.io.{ IOUtils, FileUtils }
import play.api.Logger
import play.api.libs.Files.TemporaryFile
import play.api.libs.iteratee.Enumerator
import play.api.libs.ws.WSResponseHeaders

class FakeProcessedImageHelper extends ProcessedImageHelper with Logging {
  val webService = new FakeWebService()

  val fakeFile1 = {
    val tf = TemporaryFile(new File("test/data/image1-" + Math.random() + ".png"))
    tf.file.deleteOnExit()
    FileUtils.copyFile(new File("test/data/image1.png"), tf.file)
    tf
  }

  webService.setGlobalStreamResponse { _ =>
    val header: WSResponseHeaders = new WSResponseHeaders {
      override def status: Int = 200
      override def headers: Map[String, Seq[String]] = Map("Content-Type" -> Seq("image/png"))
    }
    val body = Enumerator(IOUtils.toByteArray(new FileInputStream(fakeFile1.file)))
    (header, body)
  }
}

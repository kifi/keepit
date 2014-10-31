package com.keepit.controllers.website

import java.io.StringWriter

import com.keepit.common.logging.Logging
import controllers.AssetsBuilder
import org.apache.commons.io.IOUtils
import play.api.Play.current
import play.api.mvc.{ Action, Controller }
import play.api.{ Mode, Play }

object MarketingSiteRouter extends AssetsBuilder with Controller with Logging {

  private def fileLoad(path: String): Option[String] = {
    Play.resourceAsStream(s"/k/$path.html").map { stream =>
      val writer = new StringWriter()
      val fileStr = try {
        IOUtils.copy(stream, writer, "UTF-8")
        writer.toString
      } finally {
        stream.close()
      }
      fileStr
    }
  }

  private val cachedIndex = Map[String, Option[String]]().withDefault(file => fileLoad(file))

  private def maybeCachedIndex(file: String): Option[String] = {
    if (Play.maybeApplication.exists(_.mode == Mode.Prod)) {
      cachedIndex(file)
    } else {
      fileLoad(file)
    }
  }

  def marketingSite(path: String = "index") = {
    val file = if (path.isEmpty) "index" else path
    if (file.contains(".html")) {
      val noHtml = file.replace(".html", "")
      if (maybeCachedIndex(noHtml).nonEmpty) {
        Redirect(s"https://www.kifi.com/$noHtml")
      } else {
        NotFound(views.html.error.notFound(s"$path (try to remove the .html)"))
      }
    } else {
      maybeCachedIndex(file) map { content =>
        Ok(content).as(HTML)
      } getOrElse {
        NotFound(views.html.error.notFound(path))
      }
    }
  }
}

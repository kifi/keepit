package com.keepit.controllers.website

import java.io.StringWriter

import com.keepit.common.logging.Logging
import controllers.AssetsBuilder
import org.apache.commons.io.IOUtils
import play.api.Play.current
import play.api.mvc.{ Action, Controller }
import play.api.{ Mode, Play }

object MarketingAssets extends AssetsBuilder with Controller with Logging {

  private def fileLoad(path: String): String = {
    val stream = Play.resourceAsStream(s"/k/$path.html").get
    val writer = new StringWriter()
    val fileStr = try {
      IOUtils.copy(stream, writer, "UTF-8")
      writer.toString
    } finally {
      stream.close()
    }
    fileStr
  }

  private val cachedIndex = Map[String, String]().withDefault(file => fileLoad(file))

  private def maybeCachedIndex(file: String) = {
    if (Play.maybeApplication.exists(_.mode == Mode.Prod)) {
      cachedIndex(file)
    } else {
      fileLoad(file)
    }
  }

  def marketingSite(path: String) = Action {
    val file = if (path.isEmpty) "index" else path
    if (file.contains(".html")) {
      NotFound(s"$path not found, try to remove the .html")
    } else {
      val content = maybeCachedIndex(file)
      Ok(content).as(HTML)
    }
  }
}

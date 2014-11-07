package com.keepit.controllers.website

import java.io.StringWriter

import com.keepit.common.logging.Logging
import controllers.AssetsBuilder
import org.apache.commons.io.IOUtils
import play.api.Play.current
import play.api.mvc.{ Request, Controller }
import play.api.{ Mode, Play }

import scala.util.Try
import scala.util.matching.Regex

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

  private def landing(implicit request: Request[_]) = {
    val pickOpt = Try {
      request.getQueryString("v").map(_.toInt)
    } recover {
      case t: Throwable =>
        log.error(s"[landing] Caught exception $t while parsing queryParam(v):${request.queryString("v")}")
        None
    } get

    pickOpt match {
      case Some(idx) if (idx == 1 || idx == 2) =>
        "index." + idx
      case _ =>
        val ip = request.remoteAddress // remoteAddress looks up 'X-Forwarded-For'
        val hash = (Math.abs(ip.hashCode()) % 100) // rough
        val winner = if (hash < 50) "index.1" else "index.2"
        log.info(s"[landing] remoteAddr=${request.remoteAddress} ip=$ip winner=$winner")
        winner
    }
  }

  def marketingSite(path: String = "index", substitutions: Map[Regex, String] = Map.empty)(implicit request: Request[_]) = {
    val file = if (path.isEmpty || path == "index") {
      landing
    } else {
      path
    }
    if (file.contains(".html")) {
      val noHtml = file.replace(".html", "")
      if (maybeCachedIndex(noHtml).nonEmpty) {
        Redirect(s"https://www.kifi.com/$noHtml")
      } else {
        NotFound(views.html.error.notFound(s"$path (try to remove the .html)"))
      }
    } else {
      maybeCachedIndex(file) map { content =>
        val updatedContent = substitutions.foldLeft(content) {
          case (updatedContent, (pattern, newValue)) =>
            if (pattern.findFirstIn(updatedContent).isEmpty) {
              log.warn(s"Expected Pattern not found: $pattern in content for $path")
            }
            pattern.replaceAllIn(updatedContent, newValue)
        }
        Ok(updatedContent).as(HTML)
      } getOrElse {
        NotFound(views.html.error.notFound(path))
      }
    }
  }
}

package com.keepit.controllers.website

import java.io.StringWriter
import java.util.regex.Pattern

import com.keepit.common.logging.Logging
import com.keepit.common.http._
import controllers.AssetsBuilder
import org.apache.commons.io.IOUtils
import play.api.Play.current
import play.api.mvc.{ Request, Controller }
import play.api.{ Mode, Play }

import scala.util.Try
import scala.util.matching.Regex

object MarketingSiteRouter extends AssetsBuilder with Controller with Logging {

  def substituteMetaProperty(property: String, newContent: String): (Regex, String) = {
    val pattern = ("""<meta\s+property="""" + Pattern.quote(property) + """"\s+content=".*"\s*/?>""").r
    val newValue = s"""<meta property="$property" content="$newContent"/>"""
    pattern -> newValue
  }

  def substituteLink(rel: String, newRef: String): (Regex, String) = {
    val pattern = ("""<link\s+rel="""" + Pattern.quote(rel) + """"\s+href=".*"\s*/?>""").r
    val newValue = s"""<link rel="$rel" href="$newRef"/>"""
    pattern -> newValue
  }

  private def fileLoad(path: String): Option[String] = {
    Play.resourceAsStream(s"/marketing/$path.html").map { stream =>
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

  private trait LandingVersion {
    def version: Int
  }
  private object Version1 extends LandingVersion { val version = 1 }
  private object Version2 extends LandingVersion { val version = 2 }
  private object Version3 extends LandingVersion { val version = 3 }
  private object Version4 extends LandingVersion { val version = 4 }

  def landing(implicit request: Request[_]): String = {
    val possiblyBot = request.userAgentOpt.map(_.possiblyBot).getOrElse(true)
    val version: LandingVersion = if (possiblyBot) {
      Version4
    } else {
      val pickOpt = Try {
        request.getQueryString("v").map(_.toInt)
      } recover {
        case t: Throwable =>
          log.error(s"[landing] Caught exception $t while parsing queryParam(v):${request.queryString("v")}")
          None
      } get

      pickOpt match {
        case Some(idx) if idx == 1 => Version1
        case Some(idx) if idx == 2 => Version2
        case Some(idx) if idx == 3 => Version3
        case Some(idx) if idx == 4 => Version4
        case _ =>
          //currently we have only one version alive. When we'll restart the a/b testing the next five lines will be relevant again.
          //          val ip = request.remoteAddress // remoteAddress looks up 'X-Forwarded-For'
          //          val hash = (Math.abs(ip.hashCode()) % 100) // rough
          //          val winner = if (hash < 50) Version3 else Version1
          //          log.info(s"[landing] remoteAddr=${request.remoteAddress} ip=$ip winner=$winner")
          //          winner
          Version4
      }
    }
    s"index.${version.version}"
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

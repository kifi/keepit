package com.keepit.controllers.website

import java.io.StringWriter
import java.net.InetAddress

import com.google.common.base.Charsets
import com.google.common.hash.Hashing
import com.keepit.common.logging.Logging
import controllers.AssetsBuilder
import org.apache.commons.io.IOUtils
import play.api.Play.current
import play.api.mvc.{ Request, Controller }
import play.api.{ Mode, Play }

import scala.util.Try

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

  val hashFunction = Hashing.murmur3_32()

  def landing(implicit request: Request[_]) = {
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
      case None =>
        val ip = Try {
          request.remoteAddress // remoteAddress looks up 'X-Forwarded-For'
        } recover {
          case t: Throwable =>
            InetAddress.getLocalHost.toString // with affinity this might be sufficient
        } get
        val hasher = hashFunction.newHasher()
        val hc = hasher.putString(ip, Charsets.UTF_8).hash()
        val hash = (Math.abs(hc.asInt()) % 100) // rough
        val winner = if (hash < 50) "index.1" else "index.2"
        log.info(s"[landing] remoteAddr=${request.remoteAddress} ip=$ip hc=$hc winner=$winner")
        winner
    }
  }

  def marketingSite(path: String = "index")(implicit request: Request[_]) = {
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
        Ok(content).as(HTML)
      } getOrElse {
        NotFound(views.html.error.notFound(path))
      }
    }
  }
}

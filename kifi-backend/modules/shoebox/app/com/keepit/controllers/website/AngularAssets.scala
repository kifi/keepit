package com.keepit.controllers.website

import java.io.StringWriter

import com.keepit.common.concurrent.ExecutionContext.immediate
import com.keepit.common.logging.Logging
import controllers.AssetsBuilder
import org.apache.commons.io.IOUtils
import play.api.Play.current
import play.api.libs.iteratee.Enumerator
import play.api.mvc.Controller
import play.api.{ Mode, Play }

import scala.concurrent.Future

object AngularDistAssets extends AssetsBuilder with Controller with Logging {

  private def index() = {
    val fileStream = Play.resourceAsStream("public/ng/index_cdn.html").orElse(Play.resourceAsStream("public/ng/index.html")).get
    val writer = new StringWriter()
    IOUtils.copy(fileStream, writer, "UTF-8")
    val fileStr = writer.toString
    Enumerator(fileStr)
  }
  private lazy val cachedIndex = index()
  private def maybeCachedIndex = {
    if (Play.maybeApplication.exists(_.mode == Mode.Prod)) {
      cachedIndex
    } else {
      index()
    }
  }

  @inline
  private def reactiveEnumerator(futureSeq: Seq[Future[String]]) = {
    // Returns successful results of Futures in the order they are completed, reactively
    Enumerator.interleave(futureSeq.map { future =>
      Enumerator.flatten(future.map(r => Enumerator(r))(immediate))
    })
  }

  @inline
  private def augmentPage(splice: => Seq[Future[String]]) = {
    val idx = maybeCachedIndex
    idx.andThen(reactiveEnumerator(splice)).andThen(Enumerator.eof)
  }

  def angularApp(splice: => Seq[Future[String]] = Seq()) = {
    Ok.chunked(augmentPage(splice)).as(HTML)
  }
}

object AngularImgAssets extends AssetsBuilder with Logging

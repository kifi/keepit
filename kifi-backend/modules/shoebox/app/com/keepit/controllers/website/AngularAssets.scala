package com.keepit.controllers.website

import java.io.StringWriter

import com.keepit.common.concurrent.ExecutionContext.immediate
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.logging.Logging
import com.keepit.common.core._
import controllers.AssetsBuilder
import org.apache.commons.io.IOUtils
import play.api.Play.current
import play.api.libs.iteratee.Enumerator
import play.api.mvc.Controller
import play.api.{ Mode, Play }

import scala.concurrent.Future

object AngularDistAssets extends AssetsBuilder with Controller with Logging {

  val COMMENT_STRING = "<!-- HEADER_PLACEHOLDER -->"

  private def index(): (Enumerator[String], Enumerator[String]) = {
    val fileStream = Play.resourceAsStream("public/ng/index_cdn.html").orElse(Play.resourceAsStream("public/ng/index.html")).get
    val writer = new StringWriter()
    IOUtils.copy(fileStream, writer, "UTF-8")
    val fileStr = writer.toString
    val parts = fileStr.split(COMMENT_STRING)
    if (parts.size != 2) throw new Exception(s"no two parts for index file $fileStr")
    (Enumerator(parts(0)), Enumerator(parts(1)))

  }

  private lazy val cachedIndex: (Enumerator[String], Enumerator[String]) = index()

  private def maybeCachedIndex: (Enumerator[String], Enumerator[String]) = {
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
  private def augmentPage(headerLoad: Future[String], postload: => Seq[Future[String]]) = {
    val (idx1, idx2) = maybeCachedIndex
    val headers = headerLoad.map { f => Enumerator(f) }
    val compositPage = idx1 andThen Enumerator.flatten(headers) andThen idx2
    compositPage.andThen(reactiveEnumerator(postload)).andThen(Enumerator.eof)
  }

  def angularApp(headerLoad: Option[Future[String]] = None, postload: => Seq[Future[String]] = Seq()) = {
    val header = headerLoad match {
      case None => Future.successful("<title>Kifi</title>")
      case Some(h) => h
    }
    Ok.chunked(augmentPage(header, postload)).as(HTML)
  }
}

object AngularImgAssets extends AssetsBuilder with Logging

package com.keepit.controllers.website

import java.io.StringWriter

import com.keepit.common.concurrent.ExecutionContext.immediate
import com.keepit.common.core._
import com.keepit.common.logging.Logging
import com.keepit.common.strings.UTF8
import controllers.AssetsBuilder
import org.apache.commons.io.IOUtils
import play.api.Play.current
import play.api.libs.iteratee.Enumerator
import play.api.mvc.Controller
import play.api.{ Mode, Play }

import scala.concurrent.Future

object AngularApp extends Controller with Logging {

  val COMMENT_STRING = "<!-- HEADER_PLACEHOLDER -->"

  private def index(): (Enumerator[String], Enumerator[String]) = {
    val fileStream = Play.resourceAsStream("public/ng/index_cdn.html").orElse(Play.resourceAsStream("public/ng/index.html")).get
    val writer = new StringWriter()
    IOUtils.copy(fileStream, writer, UTF8)
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
  private def enumerateFuture[T](f: Future[T]): Enumerator[T] = {
    Enumerator.flatten(f.map(t => Enumerator(t))(immediate))
  }

  @inline
  private def enumerateFutures[T](futures: Seq[Future[T]]): Enumerator[T] = {
    // Returns successful results of Futures in the order they are completed, reactively
    Enumerator.interleave(futures map enumerateFuture)
  }

  @inline
  private def augmentPage(head: Future[String], feet: => Seq[Future[String]]): Enumerator[String] = {
    val (idx1, idx2) = maybeCachedIndex
    idx1 andThen enumerateFuture(head) andThen idx2 andThen enumerateFutures(feet) andThen Enumerator.eof
  }

  def app(headOpt: Option[Future[String]] = None, feet: => Seq[Future[String]] = Seq.empty) = {
    val head = headOpt getOrElse Future.successful("<title>Kifi</title>")
    Ok.chunked(augmentPage(head, feet)).as(HTML)
  }
}

object AngularDistAssets extends AssetsBuilder with Logging

object AngularImgAssets extends AssetsBuilder with Logging

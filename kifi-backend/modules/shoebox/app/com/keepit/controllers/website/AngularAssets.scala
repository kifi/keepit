package com.keepit.controllers.website

import java.io.StringWriter

import com.keepit.common.concurrent.ExecutionContext.immediate
import com.keepit.common.controller.{ MaybeUserRequest, UserRequest, NonUserRequest }
import com.keepit.common.core._
import com.keepit.common.crypto.CryptoSupport
import com.keepit.common.http._
import com.keepit.common.logging.Logging
import com.keepit.common.net.UserAgent
import com.keepit.common.strings.UTF8
import controllers.AssetsBuilder
import org.apache.commons.io.IOUtils
import play.api.Play.current
import play.api.libs.iteratee.Enumerator
import play.api.mvc.{ Controller, Result }
import play.api.{ Mode, Play }

import scala.concurrent.Future

object AngularApp extends Controller with Logging {

  val COMMENT_STRING = "<!-- HEADER_PLACEHOLDER -->"
  val NONCE_STRING = "<!-- NONCE_PLACEHOLDER -->"

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
  private def augmentPage(nonce: String, head: Future[String], feet: Seq[Future[String]] = Seq.empty)(implicit request: MaybeUserRequest[_]): Enumerator[String] = {
    val (idx1, idx2) = maybeCachedIndex
    val noncedIdx2 = idx2.map { body =>
      body.replaceAllLiterally(NONCE_STRING, nonce)
    }(immediate)
    val fullHead = head.map(_ + preloadStub(nonce))(immediate)
    val noncedFeet = feet.map(_.map(_.replaceAllLiterally(NONCE_STRING, nonce))(immediate))

    idx1 andThen enumerateFuture(fullHead) andThen noncedIdx2 andThen enumerateFutures(noncedFeet) andThen Enumerator.eof
  }

  def app(metaGenerator: Option[() => Future[String]] = None, feet: Seq[Future[String]] = Seq.empty)(implicit request: MaybeUserRequest[_]): Result = {
    val head = request match {
      case r: UserRequest[_] =>
        Future.successful(s"""<title id="kf-authenticated">Kifi</title>""") // a temporary silly way to indicate to the app that a user is authenticated
      case r: NonUserRequest[_] if metaGenerator.isDefined && r.userAgentOpt.orElse(Some(UserAgent.UnknownUserAgent)).exists(_.possiblyBot) =>
        metaGenerator.get()
      case _ =>
        Future.successful("<title>Kifi</title>")
    }
    val nonce = CryptoSupport.generateHexSha256("Kifi nonce s@lt" + request.id).take(16)
    Ok.chunked(augmentPage(nonce, head, feet)).as(HTML).withHeaders("X-Nonce" -> nonce)
  }

  def app(metaGenerator: () => Future[String])(implicit request: MaybeUserRequest[_]): Result = app(Some(metaGenerator))

  private def preloadStub(nonce: String) = s"""<script nonce="$nonce">window.preload=function(p,d){preload.data=preload.data||{};preload.data[p] = d};</script>\n"""

}

object AngularDistAssets extends AssetsBuilder with Logging

object AngularImgAssets extends AssetsBuilder with Logging

package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.controller.{ SearchServiceController, BrowserExtensionController, ActionAuthenticator }
import com.keepit.common.logging.Logging
import com.keepit.controllers.util.SearchControllerUtil
import com.keepit.model._
import com.keepit.model.ExperimentType.ADMIN
import com.keepit.search.engine.result.KifiPlainResult
import com.keepit.search.SearchCommander
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.{ JsNumber, JsString, JsObject }
import scala.concurrent.Future

class ExtSearchController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    shoeboxClient: ShoeboxServiceClient,
    searchCommander: SearchCommander) extends BrowserExtensionController(actionAuthenticator) with SearchServiceController with SearchControllerUtil with Logging {

  def search(
    query: String,
    filter: Option[String],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
    kifiVersion: Option[KifiVersion] = None,
    start: Option[String] = None,
    end: Option[String] = None,
    tz: Option[String] = None,
    coll: Option[String] = None,
    debug: Option[String] = None,
    withUriSummary: Boolean = false) = JsonAction.authenticated { request =>

    val userId = request.userId
    val acceptLangs: Seq[String] = request.request.acceptLanguages.map(_.code)

    val debugOpt = if (debug.isDefined && request.experiments.contains(ADMIN)) debug else None // debug is only for admin

    val decoratedResult = searchCommander.search(userId, acceptLangs, request.experiments, query, filter, maxHits, lastUUIDStr, context, predefinedConfig = None, debugOpt, withUriSummary)

    Ok(toKifiSearchResultV1(decoratedResult)).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  def search2(
    query: String,
    filter: Option[String],
    library: Option[String],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
    kifiVersion: Option[KifiVersion] = None,
    debug: Option[String] = None,
    withUriSummary: Boolean = false) = JsonAction.authenticated { request =>

    val userId = request.userId
    val acceptLangs: Seq[String] = request.request.acceptLanguages.map(_.code)

    val debugOpt = if (debug.isDefined && request.experiments.contains(ADMIN)) debug else None // debug is only for admin

    val plainResultFuture = searchCommander.search2(userId, acceptLangs, request.experiments, query, filter, library, maxHits, lastUUIDStr, context, predefinedConfig = None, debugOpt)

    val plainResultEnumerator = Enumerator.flatten(plainResultFuture.map(r => Enumerator(toKifiSearchResultV2(r).toString))(immediate))

    var decorationFutures: List[Future[String]] = Nil

    // TODO: augmentation
    // decorationFutures = augmentationFuture(plainResultFuture) :: decorationFutures

    if (withUriSummary) {
      decorationFutures = uriSummaryInfoFuture(shoeboxClient, plainResultFuture) :: decorationFutures
    }

    val decorationEnumerator = reactiveEnumerator(decorationFutures)

    val resultEnumerator = Enumerator("[").andThen(plainResultEnumerator).andThen(decorationEnumerator).andThen(Enumerator("]")).andThen(Enumerator.eof)

    Ok.chunked(resultEnumerator).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  private def augmentationFuture(plainResultFuture: Future[KifiPlainResult]): Future[String] = ??? // TODO: augmentation

  def langDetect(query: String) = JsonAction.authenticatedAsync { request =>
    val startTime = System.currentTimeMillis()
    val acceptLangs: Seq[String] = request.request.acceptLanguages.map(_.code)
    searchCommander.langDetect(query, acceptLangs).map { lang =>
      Ok(JsObject(List(
        "elapsedMillis" -> JsNumber(System.currentTimeMillis() - startTime),
        "lang" -> JsString(lang.lang)
      )))
    }
  }

  //external (from the extension)
  def warmUp() = JsonAction.authenticated { request =>
    searchCommander.warmUp(request.userId)
    Ok
  }
}


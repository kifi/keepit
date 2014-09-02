package com.keepit.controllers.website

import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.controllers.util.SearchControllerUtil
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.iteratee.Enumerator
import com.google.inject.Inject
import com.keepit.common.controller.{ WebsiteController, SearchServiceController, ActionAuthenticator }
import com.keepit.common.logging.Logging
import com.keepit.model.ExperimentType.ADMIN
import com.keepit.search.SearchCommander

import scala.concurrent.Future

class WebsiteSearchController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    shoeboxClient: ShoeboxServiceClient,
    searchCommander: SearchCommander) extends WebsiteController(actionAuthenticator) with SearchServiceController with SearchControllerUtil with Logging {

  def search(
    query: String,
    filter: Option[String],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
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
    withUriSummary: Boolean = false) = JsonAction.authenticated { request =>

    val userId = request.userId
    val acceptLangs: Seq[String] = request.request.acceptLanguages.map(_.code)

    val plainResultFuture = searchCommander.search2(userId, acceptLangs, request.experiments, query, filter, library, maxHits, lastUUIDStr, context, predefinedConfig = None)

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

  //external (from the website)
  def warmUp() = JsonAction.authenticated { request =>
    searchCommander.warmUp(request.userId)
    Ok
  }
}

package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.controller._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.logging.Logging
import com.keepit.controllers.util.SearchControllerUtil
import com.keepit.controllers.util.SearchControllerUtil.nonUser
import com.keepit.model._
import com.keepit.model.ExperimentType.ADMIN
import com.keepit.search.graph.library.LibraryIndexer
import com.keepit.search.{ AugmentationCommander, SearchCommander }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.Json
import scala.concurrent.Future

class ExtSearchController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    val shoeboxClient: ShoeboxServiceClient,
    augmentationCommander: AugmentationCommander,
    libraryIndexer: LibraryIndexer,
    searchCommander: SearchCommander,
    amazonInstanceInfo: AmazonInstanceInfo,
    val userActionsHelper: UserActionsHelper,
    implicit val publicIdConfig: PublicIdConfiguration) extends BrowserExtensionController(actionAuthenticator) with UserActions with SearchServiceController with SearchControllerUtil with Logging {

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

    val decoratedResult = searchCommander.search(userId, acceptLangs, request.experiments, query, filter, maxHits, lastUUIDStr, context, None, debugOpt, withUriSummary)

    Ok(toKifiSearchResultV1(decoratedResult)).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  def search2(
    query: String,
    filter: Option[String],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
    kifiVersion: Option[KifiVersion] = None,
    debug: Option[String] = None,
    withUriSummary: Boolean = false) = MaybeUserAction { request =>

    val libraryContextFuture = getLibraryContextFuture(None, None, request)
    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)

    val debugOpt = if (debug.isDefined && experiments.contains(ADMIN)) debug else None // debug is only for admin

    val plainResultFuture = searchCommander.search2(userId, acceptLangs, experiments, query, filter, libraryContextFuture, maxHits, lastUUIDStr, context, None, debugOpt)

    val plainResultEnumerator = safelyFlatten(plainResultFuture.map(r => Enumerator(toKifiSearchResultV2(r).toString))(immediate))

    var decorationFutures: List[Future[String]] = Nil

    if (withUriSummary) {
      decorationFutures = uriSummaryInfoFuture(plainResultFuture) :: decorationFutures
    }

    val decorationEnumerator = reactiveEnumerator(decorationFutures)

    val resultEnumerator = Enumerator("[").andThen(plainResultEnumerator).andThen(decorationEnumerator).andThen(Enumerator("]")).andThen(Enumerator.eof)

    Ok.chunked(resultEnumerator).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  //external (from the extension)
  def warmUp() = JsonAction.authenticated { request =>
    searchCommander.warmUp(request.userId)
    Ok
  }

  def instance() = HtmlAction.authenticated { request =>
    if (request.experiments.contains(ADMIN)) {
      Ok(amazonInstanceInfo.name.getOrElse(""))
    } else {
      NotFound
    }
  }
}


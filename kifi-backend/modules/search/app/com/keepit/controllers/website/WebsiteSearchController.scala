package com.keepit.controllers.website

import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.controllers.util.SearchControllerUtil
import com.keepit.controllers.util.SearchControllerUtil.nonUser
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.iteratee.Enumerator
import com.google.inject.Inject
import com.keepit.common.controller._
import com.keepit.common.logging.Logging
import com.keepit.model.ExperimentType.ADMIN
import com.keepit.search.{ LibrarySearchCommander, AugmentationCommander, SearchCommander }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import play.api.libs.json.{ JsNumber, JsObject, Json }
import com.keepit.search.graph.library.LibraryIndexer

class WebsiteSearchController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    val shoeboxClient: ShoeboxServiceClient,
    augmentationCommander: AugmentationCommander,
    libraryIndexer: LibraryIndexer,
    searchCommander: SearchCommander,
    librarySearchCommander: LibrarySearchCommander,
    val userActionsHelper: UserActionsHelper,
    implicit val publicIdConfig: PublicIdConfiguration) extends WebsiteController(actionAuthenticator) with UserActions with SearchServiceController with SearchControllerUtil with Logging {

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
    auth: Option[String],
    debug: Option[String] = None,
    withUriSummary: Boolean = false) = MaybeUserAction { request =>

    val libraryContextFuture = getLibraryContextFuture(library, auth, request)
    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)

    val debugOpt = if (debug.isDefined && experiments.contains(ADMIN)) debug else None // debug is only for admin

    val plainResultFuture = searchCommander.search2(userId, acceptLangs, experiments, query, filter, libraryContextFuture, maxHits, lastUUIDStr, context, None, debugOpt)

    val plainResultEnumerator = safelyFlatten(plainResultFuture.map(r => Enumerator(toKifiSearchResultV2(r).toString))(immediate))

    var decorationFutures: List[Future[String]] = Nil

    if (userId != nonUser) {
      val augmentationFuture = plainResultFuture.flatMap(augment(augmentationCommander, libraryIndexer.getSearcher)(userId, _).map(Json.stringify)(immediate))
      decorationFutures = augmentationFuture :: decorationFutures
    }

    if (withUriSummary) {
      decorationFutures = uriSummaryInfoFuture(plainResultFuture) :: decorationFutures
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

  def librarySearch(
    query: String,
    filter: Option[String],
    maxHits: Int,
    context: Option[String],
    debug: Option[String]) = UserAction.async { request =>

    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)

    val debugOpt = if (debug.isDefined && experiments.contains(ADMIN)) debug else None // debug is only for admin

    librarySearchCommander.librarySearch(userId, acceptLangs, experiments, query, filter, context, maxHits, None, debugOpt).map { libraryShardResult =>
      val libraryNames = getLibraryNames(libraryIndexer.getSearcher, libraryShardResult.hits.map(_.id))
      val json = JsObject(libraryShardResult.hits.map { hit => libraryNames(hit.id) -> JsNumber(hit.score) })
      Ok(Json.toJson(json))
    }
  }
}

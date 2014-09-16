package com.keepit.controllers.website

import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.db.Id
import com.keepit.controllers.util.SearchControllerUtil
import com.keepit.controllers.util.SearchControllerUtil._
import com.keepit.model.{ User, ExperimentType }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.iteratee.Enumerator
import com.google.inject.Inject
import com.keepit.common.controller.{ WebsiteController, SearchServiceController, ActionAuthenticator }
import com.keepit.common.logging.Logging
import com.keepit.model.ExperimentType.ADMIN
import com.keepit.search.{ AugmentationCommander, SearchCommander }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.RequestHeader

import scala.concurrent.Future
import play.api.libs.json.Json
import com.keepit.search.graph.library.LibraryIndexer

class WebsiteSearchController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    shoeboxClient: ShoeboxServiceClient,
    augmentationCommander: AugmentationCommander,
    libraryIndexer: LibraryIndexer,
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
    auth: Option[String],
    withUriSummary: Boolean = false) = {

    def execSearch(userId: Id[User], acceptLangs: Seq[String], experiments: Set[ExperimentType], libraryAccessAuthorized: Boolean) = {

      val plainResultFuture = searchCommander.search2(userId, acceptLangs, experiments, query, filter, library, libraryAccessAuthorized, maxHits, lastUUIDStr, context, predefinedConfig = None)

      val plainResultEnumerator = safelyFlatten(plainResultFuture.map(r => Enumerator(toKifiSearchResultV2(r).toString))(immediate))

      var decorationFutures: List[Future[String]] = Nil

      if (userId != nonUser) {
        val augmentationFuture = plainResultFuture.flatMap(augment(augmentationCommander, libraryIndexer.getSearcher, shoeboxClient)(userId, _).map(Json.stringify)(immediate))
        decorationFutures = augmentationFuture :: decorationFutures
      }

      if (withUriSummary) {
        decorationFutures = uriSummaryInfoFuture(shoeboxClient, plainResultFuture) :: decorationFutures
      }

      val decorationEnumerator = reactiveEnumerator(decorationFutures)

      val resultEnumerator = Enumerator("[").andThen(plainResultEnumerator).andThen(decorationEnumerator).andThen(Enumerator("]")).andThen(Enumerator.eof)

      Ok.chunked(resultEnumerator).withHeaders("Cache-Control" -> "private, max-age=10")
    }

    JsonAction.apply(
      authenticatedAction = { request =>
        val libraryAccessAuthorized = checkPermission(library, auth, request.request)
        execSearch(request.userId, request.request.acceptLanguages.map(_.code), request.experiments, libraryAccessAuthorized)
      },
      unauthenticatedAction = { request =>
        val libraryAccessAuthorized = checkPermission(library, auth, request)
        execSearch(nonUser, request.acceptLanguages.map(_.code), Set[ExperimentType](), libraryAccessAuthorized)
      }
    )
  }

  private def checkPermission(library: Option[String], auth: Option[String], requestHeader: RequestHeader): Boolean = {
    val cookie = Some(1) //requestHeader.cookies.get(???) // TODO
    (library, auth, cookie) match {
      case (Some(libPublicId), Some(authCode), Some(cookie)) =>
        // TODO: call shoebox to check permission
        true
      case _ =>
        false
    }
  }

  //external (from the website)
  def warmUp() = JsonAction.authenticated { request =>
    searchCommander.warmUp(request.userId)
    Ok
  }
}

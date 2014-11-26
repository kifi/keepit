package com.keepit.controllers.mobile

import play.api.libs.json._
import com.google.inject.Inject
import com.keepit.common.controller.{ SearchServiceController, UserActions, UserActionsHelper }
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.search.util.IdFilterCompressor
import com.keepit.search.{ LibrarySearchCommander, SearchCommander }
import com.keepit.model.ExperimentType._
import play.api.libs.json.JsArray
import play.api.libs.json.JsString
import com.keepit.search.graph.library.{ LibraryIndexer, LibraryIndexable }
import com.keepit.controllers.util.SearchControllerUtil
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class MobileSearchController @Inject() (
    val userActionsHelper: UserActionsHelper,
    implicit val publicIdConfig: PublicIdConfiguration,
    val shoeboxClient: ShoeboxServiceClient,
    searchCommander: SearchCommander,
    librarySearchCommander: LibrarySearchCommander,
    libraryIndexer: LibraryIndexer) extends UserActions with SearchServiceController with SearchControllerUtil with Logging {

  def searchV1(
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
    withUriSummary: Boolean = false) = UserAction { request =>

    val userId = request.userId
    val acceptLangs: Seq[String] = request.request.acceptLanguages.map(_.code)

    val decoratedResult = searchCommander.search(userId, acceptLangs, request.experiments, query, filter, maxHits, lastUUIDStr, context, predefinedConfig = None, None, withUriSummary)

    Ok(toKifiSearchResultV1(decoratedResult, sanitize = true)).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  def warmUp() = UserAction { request =>
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

    librarySearchCommander.librarySearch(userId, acceptLangs, experiments, query, filter, context, maxHits, None, debugOpt).flatMap { librarySearchResult =>
      val librarySearcher = libraryIndexer.getSearcher
      val libraryById = librarySearchResult.hits.flatMap(hit => LibraryIndexable.getRecord(librarySearcher, hit.id)).map(record => record.id -> record).toMap
      val futureUsers = shoeboxClient.getBasicUsers(libraryById.values.map(_.ownerId).toSeq.distinct)
      val futureLibraryStatistics = shoeboxClient.getBasicLibraryStatistics(libraryById.keySet)
      for {
        usersById <- futureUsers
        libraryStatisticsById <- futureLibraryStatistics
      } yield {
        val hitsArray = JsArray(librarySearchResult.hits.flatMap { hit =>
          libraryById.get(hit.id).map { library =>
            val statistics = libraryStatisticsById(library.id)
            val description = library.description.getOrElse("")
            Json.obj(
              "id" -> Library.publicId(hit.id),
              "score" -> hit.score,
              "name" -> library.name,
              "description" -> description,
              "owner" -> usersById(library.ownerId),
              "memberCount" -> statistics.memberCount,
              "keepCount" -> statistics.keepCount,
              "mostRelevantKeep" -> hit.keep.map { case (_, keepRecord) => Json.obj("id" -> keepRecord.externalId, "title" -> JsString(keepRecord.title.getOrElse("")), "url" -> keepRecord.url) }
            )
          }
        })
        val result = Json.obj(
          "query" -> query,
          "context" -> IdFilterCompressor.fromSetToBase64(librarySearchResult.idFilter),
          "experimentId" -> librarySearchResult.searchExperimentId,
          "hits" -> hitsArray
        )
        Ok(result)
      }
    }
  }
}


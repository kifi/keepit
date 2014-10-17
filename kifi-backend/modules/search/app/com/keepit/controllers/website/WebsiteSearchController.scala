package com.keepit.controllers.website

import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.crypto.{ PublicIdConfiguration }
import com.keepit.controllers.util.{ BasicLibrary, SearchControllerUtil }
import com.keepit.shoebox.ShoeboxServiceClient
import com.google.inject.Inject
import com.keepit.common.controller._
import com.keepit.common.logging.Logging
import com.keepit.model.ExperimentType.ADMIN
import com.keepit.search._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import play.api.libs.json._
import com.keepit.search.graph.library.{ LibraryIndexable, LibraryIndexer }
import play.api.libs.json.JsArray
import com.keepit.model._
import com.keepit.social.BasicUser
import com.keepit.search.util.IdFilterCompressor
import com.keepit.common.db.{ Id }
import com.keepit.common.core._
import com.keepit.controllers.website.WebsiteSearchController._
import com.keepit.search.augmentation.{ AugmentedItem, AugmentationCommander }
import com.keepit.common.json

class WebsiteSearchController @Inject() (
    val userActionsHelper: UserActionsHelper,
    val shoeboxClient: ShoeboxServiceClient,
    augmentationCommander: AugmentationCommander,
    libraryIndexer: LibraryIndexer,
    searchCommander: SearchCommander,
    librarySearchCommander: LibrarySearchCommander,
    implicit val publicIdConfig: PublicIdConfiguration) extends UserActions with SearchServiceController with SearchControllerUtil with Logging {

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
    withUriSummary: Boolean = false) = UserAction { request =>

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
    withUriSummary: Boolean = false) = MaybeUserAction.async { request =>

    val libraryContextFuture = getLibraryContextFuture(library, auth, request)
    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)

    val debugOpt = if (debug.isDefined && experiments.contains(ADMIN)) debug else None // debug is only for admin

    searchCommander.search2(userId, acceptLangs, experiments, query, filter, libraryContextFuture, maxHits, lastUUIDStr, context, None, debugOpt).flatMap { kifiPlainResult =>

      val futureWebsiteSearchHits = if (kifiPlainResult.hits.isEmpty) {
        Future.successful((Seq.empty[JsObject], Seq.empty[BasicUser], Seq.empty[BasicLibrary]))
      } else {

        val futureUriSummaries = {
          val uriIds = kifiPlainResult.hits.map(hit => Id[NormalizedURI](hit.id))
          shoeboxClient.getUriSummaries(uriIds)
        }

        augment(augmentationCommander, userId, kifiPlainResult).flatMap {
          case augmentedItems => {
            val librarySearcher = libraryIndexer.getSearcher
            val (allSecondaryFields, userIds, libraryIds) = AugmentedItem.writesAugmentationFields(librarySearcher, userId, maxKeepersShown, maxLibrariesShown, maxTagsShown, augmentedItems)
            val futureUsers = shoeboxClient.getBasicUsers(userIds)

            val futureJsHits = futureUriSummaries.map { summaries =>
              (kifiPlainResult.hits zip allSecondaryFields).map {
                case (hit, secondaryFields) => {
                  val primaryFields = Json.obj(
                    "title" -> hit.title,
                    "url" -> hit.url,
                    "score" -> hit.finalScore,
                    "summary" -> summaries(Id(hit.id))
                  )
                  json.minify(primaryFields ++ secondaryFields)
                }
              }
            }

            val libraries = {
              val libraryById = getBasicLibraries(librarySearcher, libraryIds.toSet)
              libraryIds.map(libraryById(_))
            }

            for {
              usersById <- futureUsers
              jsHits <- futureJsHits
            } yield (jsHits, userIds.map(usersById(_)), libraries)
          }
        }
      }

      futureWebsiteSearchHits.imap {
        case (hits: Seq[JsObject], users: Seq[BasicUser], libraries: Seq[BasicLibrary]) =>
          val result = Json.obj(
            "uuid" -> kifiPlainResult.uuid,
            "context" -> IdFilterCompressor.fromSetToBase64(kifiPlainResult.idFilter),
            "experimentId" -> kifiPlainResult.searchExperimentId,
            "mayHaveMore" -> kifiPlainResult.mayHaveMoreHits,
            "myTotal" -> kifiPlainResult.myTotal,
            "friendsTotal" -> kifiPlainResult.friendsTotal,
            "othersTotal" -> kifiPlainResult.othersTotal,
            "hits" -> hits,
            "libraries" -> libraries,
            "users" -> users
          )
          Ok(result)
      }
    }
  }

  //external (from the website)
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

    librarySearchCommander.librarySearch(userId, acceptLangs, experiments, query, filter, context, maxHits, None, debugOpt).map { libraryShardResult =>
      val librarySearcher = libraryIndexer.getSearcher
      val libraries = libraryShardResult.hits.map(_.id).map { libId => libId -> LibraryIndexable.getRecord(librarySearcher, libId) }.toMap
      val json = JsArray(libraryShardResult.hits.map { hit =>
        val library = libraries(hit.id)
        Json.obj(
          "id" -> Library.publicId(hit.id),
          "score" -> hit.score,
          "name" -> library.map(_.name),
          "description" -> library.map(_.description.getOrElse("")),
          "mostRelevantKeep" -> hit.keep.map { case (_, keepRecord) => Json.obj("id" -> keepRecord.externalId, "title" -> JsString(keepRecord.title.getOrElse("")), "url" -> keepRecord.url) }
        )
      })
      Ok(Json.toJson(json))
    }
  }
}

object WebsiteSearchController {

  private[WebsiteSearchController] val maxKeepersShown = 20
  private[WebsiteSearchController] val maxLibrariesShown = 10
  private[WebsiteSearchController] val maxTagsShown = 15
}

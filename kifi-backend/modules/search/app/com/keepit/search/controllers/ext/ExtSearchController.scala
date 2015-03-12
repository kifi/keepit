package com.keepit.search.controllers.ext

import com.google.inject.Inject
import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.controller._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.search.controllers.util.{ SearchControllerUtil }
import com.keepit.model._
import com.keepit.model.ExperimentType.ADMIN
import com.keepit.search.index.Searcher
import com.keepit.search.index.graph.library.LibraryIndexer
import com.keepit.search.{ UriSearchCommander }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.BasicUser
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import com.keepit.search.augmentation.{ AugmentedItem, AugmentationCommander }
import com.keepit.common.json
import com.keepit.common.core._

import scala.concurrent.Future

object ExtSearchController {
  private[ExtSearchController] val maxKeepersShown = 20
  private[ExtSearchController] val maxLibrariesShown = 10
  private[ExtSearchController] val maxTagsShown = 15
}

class ExtSearchController @Inject() (
    val userActionsHelper: UserActionsHelper,
    val shoeboxClient: ShoeboxServiceClient,
    augmentationCommander: AugmentationCommander,
    libraryIndexer: LibraryIndexer,
    searchCommander: UriSearchCommander,
    implicit val publicIdConfig: PublicIdConfiguration) extends UserActions with SearchServiceController with SearchControllerUtil with Logging {

  import ExtSearchController._

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
    withUriSummary: Boolean = false) = UserAction { request =>

    val userId = request.userId
    val acceptLangs: Seq[String] = request.request.acceptLanguages.map(_.code)

    val debugOpt = if (debug.isDefined && request.experiments.contains(ADMIN)) debug else None // debug is only for admin

    val decoratedResult = searchCommander.search(userId, acceptLangs, request.experiments, query, filter, maxHits, lastUUIDStr, context, None, debugOpt, withUriSummary)

    Ok(toKifiSearchResultV1(decoratedResult)).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  def search2(
    query: String,
    maxHits: Int,
    filter: Option[String],
    lastUUIDStr: Option[String],
    context: Option[String],
    extVersion: Option[KifiExtVersion],
    debug: Option[String] = None) = UserAction { request =>

    val libraryContextFuture = getLibraryFilterFuture(None, None, request)
    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)
    val filterFuture = getUserFilterFuture(filter)

    val debugOpt = if (debug.isDefined && experiments.contains(ADMIN)) debug else None // debug is only for admin

    val plainResultFuture = searchCommander.searchUris(userId, acceptLangs, experiments, query, filterFuture, libraryContextFuture, maxHits, lastUUIDStr, context, None, debugOpt)
    val plainResultEnumerator = safelyFlatten(plainResultFuture.map(r => Enumerator(toKifiSearchResultV2(r).toString))(immediate))

    val augmentationFuture = plainResultFuture.flatMap { kifiPlainResult =>
      getAugmentedItems(augmentationCommander)(userId, kifiPlainResult).flatMap { augmentedItems =>
        val librarySearcher = libraryIndexer.getSearcher
        val (augmentationFields, futureBasicUsersAndLibraries) = writesAugmentationFields(librarySearcher, userId, maxKeepersShown, maxLibrariesShown, maxTagsShown, augmentedItems)

        val hitsJson = augmentationFields.map(json.minify)

        futureBasicUsersAndLibraries.imap {
          case (users, libraries) =>

            val librariesJson = libraries.map { library =>
              json.minify(Json.obj("id" -> library.id, "name" -> library.name, "color" -> library.color, "path" -> library.path, "secret" -> library.isSecret))
            }

            Json.obj("hits" -> hitsJson, "users" -> users, "libraries" -> librariesJson)
        }
      }
    }
    val augmentationEnumerator = safelyFlatten(augmentationFuture.map(aug => Enumerator(Json.stringify(aug)))(immediate))

    if (request.headers.get("Accept").exists(_ == "text/plain")) {
      Ok.chunked(plainResultEnumerator
        .andThen(augmentationEnumerator)
        .andThen(Enumerator.eof)).as(TEXT)
    } else { // JSON format last used by extension 3.3.10
      Ok.chunked(Enumerator("[").andThen(plainResultEnumerator)
        .andThen(Enumerator(",")).andThen(augmentationEnumerator)
        .andThen(Enumerator("]").andThen(Enumerator.eof))).as(JSON)
    } withHeaders ("Cache-Control" -> "private, max-age=10")
  }

  private def writesAugmentationFields(
    librarySearcher: Searcher,
    userId: Id[User],
    maxKeepersShown: Int,
    maxLibrariesShown: Int,
    maxTagsShown: Int,
    augmentedItems: Seq[AugmentedItem]): (Seq[JsObject], Future[(Seq[BasicUser], Seq[BasicLibrary])]) = {

    val limitedAugmentationInfos = augmentedItems.map(_.toLimitedAugmentationInfo(maxKeepersShown, maxLibrariesShown, maxTagsShown))

    val allKeepersShown = limitedAugmentationInfos.map(_.keepers)
    val allLibrariesShown = limitedAugmentationInfos.map(_.libraries)

    val userIds = ((allKeepersShown.flatten ++ allLibrariesShown.flatMap(_.map(_._2))).toSet - userId).toSeq
    val userIndexById = userIds.zipWithIndex.toMap + (userId -> -1)

    val libraryRecordsAndVisibilityById = getLibraryRecordsAndVisibility(librarySearcher, allLibrariesShown.flatMap(_.map(_._1)).toSet)

    val libraryIds = libraryRecordsAndVisibilityById.keys.toSeq // libraries that are missing from the index are implicitly dropped here (race condition)
    val libraryIndexById = libraryIds.zipWithIndex.toMap

    val futureBasicUsersAndLibraries = {
      val libraryOwnerIds = libraryRecordsAndVisibilityById.values.map(_._1.ownerId)
      shoeboxClient.getBasicUsers(userIds ++ libraryOwnerIds).map { usersById =>
        val users = userIds.map(usersById(_))
        val libraries = libraryIds.map { libId =>
          val (library, visibility) = libraryRecordsAndVisibilityById(libId)
          val owner = usersById(library.ownerId)
          makeBasicLibrary(library, visibility, owner)
        }
        (users, libraries)
      }
    }

    val secrecies = augmentedItems.map(_.isSecret(librarySearcher))

    val augmentationFields = (limitedAugmentationInfos zip secrecies).map {
      case (limitedInfo, secret) =>

        val keepersIndices = limitedInfo.keepers.map(userIndexById(_))
        val librariesIndices = limitedInfo.libraries.collect {
          case (libraryId, keeperId) if libraryIndexById.contains(libraryId) => Seq(libraryIndexById(libraryId), userIndexById(keeperId))
        }.flatten

        Json.obj(
          "keepers" -> keepersIndices,
          "keepersOmitted" -> limitedInfo.keepersOmitted,
          "keepersTotal" -> limitedInfo.keepersTotal,
          "libraries" -> librariesIndices,
          "librariesOmitted" -> limitedInfo.librariesOmitted,
          "tags" -> limitedInfo.tags,
          "tagsOmitted" -> limitedInfo.tagsOmitted,
          "secret" -> secret
        )
    }
    (augmentationFields, futureBasicUsersAndLibraries)
  }

  //external (from the extension)
  def warmUp() = UserAction { request =>
    searchCommander.warmUp(request.userId)
    Ok
  }
}

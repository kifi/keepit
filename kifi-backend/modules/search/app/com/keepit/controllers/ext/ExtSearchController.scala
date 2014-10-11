package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.controller._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.logging.Logging
import com.keepit.controllers.util.SearchControllerUtil
import com.keepit.model._
import com.keepit.model.ExperimentType.ADMIN
import com.keepit.search.graph.library.LibraryIndexer
import com.keepit.search.{ AugmentationCommander, SearchCommander }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import play.api.libs.json.JsBoolean
import play.api.libs.json.JsObject

object ExtSearchController {
  private[ExtSearchController] val maxKeepersShown = 20
  private[ExtSearchController] val maxLibrariesShown = 10
  private[ExtSearchController] val maxTagsShown = 15

  @inline private def canBeOmitted(value: JsValue): Boolean = value match {
    case JsNull | JsBoolean(false) | JsString("") | JsArray(Seq()) => true
    case JsNumber(zero) if zero == 0 => true
    case _ => false
  }

  def compactJson(fullJson: JsObject): JsObject = JsObject(fullJson.fields.filterNot { case (key, value) => canBeOmitted(value) })
}

class ExtSearchController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    val shoeboxClient: ShoeboxServiceClient,
    augmentationCommander: AugmentationCommander,
    libraryIndexer: LibraryIndexer,
    searchCommander: SearchCommander,
    val userActionsHelper: UserActionsHelper,
    implicit val publicIdConfig: PublicIdConfiguration) extends BrowserExtensionController(actionAuthenticator) with UserActions with SearchServiceController with SearchControllerUtil with Logging {

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
    withUriSummary: Boolean = false) = JsonAction.authenticated { request =>

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

    val libraryContextFuture = getLibraryContextFuture(None, None, request)
    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)

    val debugOpt = if (debug.isDefined && experiments.contains(ADMIN)) debug else None // debug is only for admin

    val plainResultFuture = searchCommander.search2(userId, acceptLangs, experiments, query, filter, libraryContextFuture, maxHits, lastUUIDStr, context, None, debugOpt)
    val plainResultEnumerator = safelyFlatten(plainResultFuture.map(r => Enumerator(toKifiSearchResultV2(r).toString))(immediate))

    val augmentationFuture = plainResultFuture.flatMap { kifiPlainResult =>
      augment(augmentationCommander, userId, kifiPlainResult).flatMap { augmentedItems =>
        val allKeepersShown = augmentedItems.map(_.relatedKeepers.take(maxKeepersShown))
        val allLibrariesShown = augmentedItems.map(_.libraries.take(maxLibrariesShown))

        val userIds = ((allKeepersShown.flatten ++ allLibrariesShown.flatMap(_.map(_._2))).toSet - userId).toSeq
        val futureUsers = shoeboxClient.getBasicUsers(userIds)
        val userIndexById = userIds.zipWithIndex.toMap + (userId -> -1)

        val libraryById = getBasicLibraries(libraryIndexer.getSearcher, allLibrariesShown.flatMap(_.map(_._1)).toSet)
        val (libraryIds, libraries) = libraryById.toSeq.unzip
        val libraryIndexById = libraryIds.zipWithIndex.toMap

        val hitsJson = augmentedItems.zipWithIndex.map {
          case (augmentedItem, itemIndex) =>
            val secret = augmentedItem.isSecret(libraryById(_).isSecret)

            val keepersShown = allKeepersShown(itemIndex).map(userIndexById(_))
            val keepersOmitted = augmentedItem.relatedKeepers.size - keepersShown.size
            val keepersTotal = augmentedItem.keepersTotal

            val librariesShown = allLibrariesShown(itemIndex).map { case (libraryId, keeperId) => (libraryIndexById(libraryId), userIndexById(keeperId)) }
            val librariesOmitted = augmentedItem.libraries.size - librariesShown.size

            val tagsShown = augmentedItem.tags.take(maxTagsShown)
            val tagsOmitted = augmentedItem.tags.size - tagsShown.size

            val fullJson = Json.obj(
              "secret" -> JsBoolean(secret),
              "keepers" -> keepersShown,
              "keepersOmitted" -> keepersOmitted,
              "keepersTotal" -> keepersTotal,
              "libraries" -> librariesShown.flatMap { case (libraryIndex, keeperIndex) => Seq(libraryIndex, keeperIndex) },
              "librariesOmitted" -> librariesOmitted,
              "tags" -> tagsShown,
              "tagsOmitted" -> tagsOmitted
            )
            compactJson(fullJson)
        }
        futureUsers.map { usersById =>
          val users = userIds.map(usersById(_))
          val librariesJson = libraries.map { library => compactJson(Json.obj("id" -> library.id, "name" -> library.name, "secret" -> library.isSecret)) }
          Json.obj("hits" -> hitsJson, "libraries" -> librariesJson, "users" -> users)
        }
      }
    }

    val augmentationEnumerator = reactiveEnumerator(Seq(augmentationFuture.map(Json.stringify)(immediate)))

    val resultEnumerator = Enumerator("[").andThen(plainResultEnumerator).andThen(augmentationEnumerator).andThen(Enumerator("]")).andThen(Enumerator.eof)

    Ok.chunked(resultEnumerator).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  //external (from the extension)
  def warmUp() = JsonAction.authenticated { request =>
    searchCommander.warmUp(request.userId)
    Ok
  }
}

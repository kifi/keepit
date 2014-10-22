package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.controller._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.logging.Logging
import com.keepit.controllers.util.{ SearchControllerUtil }
import com.keepit.model._
import com.keepit.model.ExperimentType.ADMIN
import com.keepit.search.graph.library.LibraryIndexer
import com.keepit.search.{ SearchCommander }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import com.keepit.search.augmentation.{ AugmentationCommander }
import com.keepit.common.json

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
    searchCommander: SearchCommander,
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

    val libraryContextFuture = getLibraryContextFuture(None, None, request)
    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)

    val debugOpt = if (debug.isDefined && experiments.contains(ADMIN)) debug else None // debug is only for admin

    val plainResultFuture = searchCommander.search2(userId, acceptLangs, experiments, query, filter, libraryContextFuture, maxHits, lastUUIDStr, context, None, debugOpt)
    val plainResultEnumerator = safelyFlatten(plainResultFuture.map(r => Enumerator(toKifiSearchResultV2(r).toString))(immediate))

    val augmentationFuture = plainResultFuture.flatMap { kifiPlainResult =>
      val librarySearcher = libraryIndexer.getSearcher
      augment(augmentationCommander, librarySearcher)(userId, maxKeepersShown, maxLibrariesShown, maxTagsShown, kifiPlainResult).flatMap {
        case (allSecondaryFields, userIds, libraryIds) =>

          val librariesById = getBasicLibraries(librarySearcher, libraryIds.toSet)

          val futureUsers = {
            val libraryOwnerIds = librariesById.values.map(_.ownerId)
            shoeboxClient.getBasicUsers(userIds ++ libraryOwnerIds)
          }

          val hitsJson = allSecondaryFields.map(json.minify)

          futureUsers.map { usersById =>
            val users = userIds.map(usersById(_))
            val libraries = libraryIds.map { libId =>
              val library = librariesById(libId)
              val owner = usersById(library.ownerId)
              BasicLibrary.writeWithPath(library, owner)
            }
            Json.obj("hits" -> hitsJson, "users" -> users, "libraries" -> libraries)
          }
      }
    }

    val augmentationEnumerator = reactiveEnumerator(Seq(augmentationFuture.map(Json.stringify)(immediate)))

    val resultEnumerator = Enumerator("[").andThen(plainResultEnumerator).andThen(augmentationEnumerator).andThen(Enumerator("]")).andThen(Enumerator.eof)

    Ok.chunked(resultEnumerator).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  //external (from the extension)
  def warmUp() = UserAction { request =>
    searchCommander.warmUp(request.userId)
    Ok
  }
}

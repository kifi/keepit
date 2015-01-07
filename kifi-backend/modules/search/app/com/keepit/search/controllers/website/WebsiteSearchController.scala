package com.keepit.search.controllers.website

import com.keepit.common.akka.SafeFuture
import com.keepit.common.crypto.{ PublicIdConfiguration }
import com.keepit.search.controllers.util.{ SearchControllerUtil }
import com.keepit.shoebox.ShoeboxServiceClient
import com.google.inject.Inject
import com.keepit.common.controller._
import com.keepit.common.logging.Logging
import com.keepit.model.ExperimentType.ADMIN
import com.keepit.search._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import play.api.libs.json._
import com.keepit.search.index.graph.library.{ LibraryIndexable, LibraryIndexer }
import play.api.libs.json.JsArray
import com.keepit.model._
import com.keepit.search.util.IdFilterCompressor
import com.keepit.common.db.{ Id }
import com.keepit.common.core._
import com.keepit.search.controllers.website.WebsiteSearchController._
import com.keepit.search.augmentation.{ AugmentationCommander }
import com.keepit.social.BasicUser

class WebsiteSearchController @Inject() (
    val userActionsHelper: UserActionsHelper,
    val shoeboxClient: ShoeboxServiceClient,
    augmentationCommander: AugmentationCommander,
    libraryIndexer: LibraryIndexer,
    uriSearchCommander: UriSearchCommander,
    librarySearchCommander: LibrarySearchCommander,
    userSearchCommander: UserSearchCommander,
    implicit val publicIdConfig: PublicIdConfiguration) extends UserActions with SearchServiceController with SearchControllerUtil with Logging {

  def search2(
    query: String,
    filter: Option[String],
    library: Option[String],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
    auth: Option[String],
    debug: Option[String] = None) = MaybeUserAction.async { request =>

    val libraryContextFuture = getLibraryContextFuture(library, auth, request)
    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)

    val debugOpt = if (debug.isDefined && experiments.contains(ADMIN)) debug else None // debug is only for admin

    uriSearchCommander.search2(userId, acceptLangs, experiments, query, filter, libraryContextFuture, maxHits, lastUUIDStr, context, None, debugOpt).flatMap { kifiPlainResult =>

      val futureWebsiteSearchHits = if (kifiPlainResult.hits.isEmpty) {
        Future.successful((Seq.empty[JsObject], Seq.empty[BasicUser], Seq.empty[BasicLibrary]))
      } else {

        val uriIds = kifiPlainResult.hits.map(hit => Id[NormalizedURI](hit.id))
        val futureUriSummaries = shoeboxClient.getUriSummaries(uriIds)
        val futureBasicKeeps = {
          if (userId == SearchControllerUtil.nonUser) {
            Future.successful(Map.empty[Id[NormalizedURI], Set[BasicKeep]].withDefaultValue(Set.empty))
          } else {
            shoeboxClient.getBasicKeeps(userId, uriIds.toSet)
          }
        }

        getAugmentedItems(augmentationCommander)(userId, kifiPlainResult).flatMap { augmentedItems =>
          val librarySearcher = libraryIndexer.getSearcher
          val (allSecondaryFields, futureBasicUsersAndLibraries) = writesAugmentationFields(librarySearcher, userId, maxKeepersShown, maxLibrariesShown, maxTagsShown, augmentedItems)

          val futureJsHits = for {
            summaries <- futureUriSummaries
            basicKeeps <- futureBasicKeeps
          } yield {
            kifiPlainResult.hits.zipWithIndex.map {
              case (hit, index) => {
                val uriId = Id[NormalizedURI](hit.id)
                val keeps = basicKeeps.getOrElse(uriId, Set.empty)
                val secret = augmentedItems(index).isSecret(librarySearcher)
                val primaryFields = Json.obj(
                  "title" -> hit.title,
                  "url" -> hit.url,
                  "score" -> hit.finalScore,
                  "summary" -> summaries(uriId),
                  "keeps" -> keeps
                )
                val secondaryFields = allSecondaryFields(index)
                primaryFields ++ secondaryFields
              }
            }
          }
          for {
            (users, libraries) <- futureBasicUsersAndLibraries
            jsHits <- futureJsHits
          } yield {
            (jsHits, users, libraries)
          }
        }
      }

      futureWebsiteSearchHits.imap {
        case (hits, users, libraries) =>
          val librariesJson = libraries.map { library =>
            Json.obj("id" -> library.id, "name" -> library.name, "path" -> library.path, "visibility" -> library.visibility, "secret" -> library.isSecret) //todo(Léo): remove secret field
          }
          val result = Json.obj(
            "uuid" -> kifiPlainResult.uuid,
            "context" -> IdFilterCompressor.fromSetToBase64(kifiPlainResult.idFilter),
            "experimentId" -> kifiPlainResult.searchExperimentId,
            "mayHaveMore" -> kifiPlainResult.mayHaveMoreHits,
            "myTotal" -> kifiPlainResult.myTotal,
            "friendsTotal" -> kifiPlainResult.friendsTotal,
            "othersTotal" -> kifiPlainResult.othersTotal,
            "hits" -> hits,
            "libraries" -> librariesJson,
            "users" -> users
          )
          Ok(result)
      }
    }
  }

  //external (from the website)
  def warmUp() = UserAction { request =>
    uriSearchCommander.warmUp(request.userId)
    Ok
  }

  def search(
    query: String,
    filter: Option[String],
    maxUris: Int,
    uriContext: Option[String],
    lastUUIDStr: Option[String],
    maxLibraries: Int,
    libraryContext: Option[String],
    maxUsers: Int,
    userContext: Option[String],
    debug: Option[String]) = UserAction.async { request =>

    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)
    val debugOpt = if (debug.isDefined && experiments.contains(ADMIN)) debug else None // debug is only for admin

    // Uri Search

    val futureUriSearchResult = if (maxUris <= 0) Future.successful(JsNull) else {
      uriSearchCommander.search2(userId, acceptLangs, experiments, query, filter, Future.successful(LibraryContext.None), maxUris, lastUUIDStr, uriContext, None, debugOpt).flatMap { uriSearchResult =>

        val futureUriSearchHits = if (uriSearchResult.hits.isEmpty) {
          Future.successful((Seq.empty[JsObject], Seq.empty[BasicUser], Seq.empty[BasicLibrary]))
        } else {

          val uriIds = uriSearchResult.hits.map(hit => Id[NormalizedURI](hit.id))
          val futureUriSummaries = shoeboxClient.getUriSummaries(uriIds)
          val futureBasicKeeps = {
            if (userId == SearchControllerUtil.nonUser) {
              Future.successful(Map.empty[Id[NormalizedURI], Set[BasicKeep]].withDefaultValue(Set.empty))
            } else {
              shoeboxClient.getBasicKeeps(userId, uriIds.toSet)
            }
          }

          getAugmentedItems(augmentationCommander)(userId, uriSearchResult).flatMap { augmentedItems =>
            val librarySearcher = libraryIndexer.getSearcher
            val (allSecondaryFields, futureBasicUsersAndLibraries) = writesAugmentationFields(librarySearcher, userId, maxKeepersShown, maxLibrariesShown, maxTagsShown, augmentedItems)

            val futureJsHits = for {
              summaries <- futureUriSummaries
              basicKeeps <- futureBasicKeeps
            } yield {
              uriSearchResult.hits.zipWithIndex.map {
                case (hit, index) => {
                  val uriId = Id[NormalizedURI](hit.id)
                  val keeps = basicKeeps.getOrElse(uriId, Set.empty)
                  val secret = augmentedItems(index).isSecret(librarySearcher)
                  val primaryFields = Json.obj(
                    "title" -> hit.title,
                    "url" -> hit.url,
                    "score" -> hit.finalScore,
                    "summary" -> summaries(uriId),
                    "keeps" -> keeps
                  )
                  val secondaryFields = allSecondaryFields(index)
                  primaryFields ++ secondaryFields
                }
              }
            }
            for {
              (users, libraries) <- futureBasicUsersAndLibraries
              jsHits <- futureJsHits
            } yield {
              (jsHits, users, libraries)
            }
          }

        }

        futureUriSearchHits.imap {
          case (hits, users, libraries) =>
            val librariesJson = libraries.map { library =>
              Json.obj("id" -> library.id, "name" -> library.name, "path" -> library.path, "visibility" -> library.visibility)
            }
            Json.obj(
              "uuid" -> uriSearchResult.uuid,
              "context" -> IdFilterCompressor.fromSetToBase64(uriSearchResult.idFilter),
              "mayHaveMore" -> uriSearchResult.mayHaveMoreHits,
              "myTotal" -> uriSearchResult.myTotal,
              "friendsTotal" -> uriSearchResult.friendsTotal,
              "othersTotal" -> uriSearchResult.othersTotal,
              "hits" -> hits,
              "libraries" -> librariesJson,
              "users" -> users
            )
        }
      }
    }

    // Library Search

    val futureLibrarySearchResult = if (maxLibraries <= 0) Future.successful(JsNull) else {
      librarySearchCommander.librarySearch(userId, acceptLangs, experiments, query, filter, libraryContext, maxLibraries, None, debugOpt, None).flatMap { librarySearchResult =>
        val librarySearcher = libraryIndexer.getSearcher
        val libraryRecordsAndVisibilityById = getLibraryRecordsAndVisibility(librarySearcher, librarySearchResult.hits.map(_.id).toSet)
        val futureUsers = shoeboxClient.getBasicUsers(libraryRecordsAndVisibilityById.values.map(_._1.ownerId).toSeq.distinct)
        val futureLibraryStatistics = shoeboxClient.getBasicLibraryStatistics(libraryRecordsAndVisibilityById.keySet)
        for {
          usersById <- futureUsers
          libraryStatisticsById <- futureLibraryStatistics
        } yield {
          val hitsArray = JsArray(librarySearchResult.hits.flatMap { hit =>
            libraryRecordsAndVisibilityById.get(hit.id).map {
              case (library, visibility) =>
                val owner = usersById(library.ownerId)
                val path = Library.formatLibraryPath(owner.username, library.slug)
                val statistics = libraryStatisticsById(library.id)
                val description = library.description.getOrElse("")
                Json.obj(
                  "id" -> Library.publicId(hit.id),
                  "score" -> hit.score,
                  "name" -> library.name,
                  "description" -> description,
                  "path" -> path,
                  "visibility" -> visibility,
                  "owner" -> owner,
                  "memberCount" -> statistics.memberCount,
                  "keepCount" -> statistics.keepCount
                )
            }
          })
          Json.obj(
            "context" -> IdFilterCompressor.fromSetToBase64(librarySearchResult.idFilter),
            "hits" -> hitsArray
          )
        }
      }
    }

    // User Search

    val futureUserSearchResult = if (maxUsers <= 0) Future.successful(JsNull) else SafeFuture {
      val userResult = userSearchCommander.searchUsers(Some(userId), query, maxUsers, userContext, filter, excludeSelf = true)
      Json.obj(
        "context" -> userResult.context,
        "hits" -> userResult.hits
      )
    }

    for {
      uriSearchResult <- futureUriSearchResult
      librarySearchResult <- futureLibrarySearchResult
      userSearchResult <- futureUserSearchResult
    } yield {
      val json = Json.obj(
        "query" -> query,
        "uris" -> uriSearchResult,
        "libraries" -> librarySearchResult,
        "users" -> userSearchResult
      )
      Ok(json)
    }
  }
}

object WebsiteSearchController {

  private[WebsiteSearchController] val maxKeepersShown = 20
  private[WebsiteSearchController] val maxLibrariesShown = 10
  private[WebsiteSearchController] val maxTagsShown = 15
}

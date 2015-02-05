package com.keepit.search.controllers.website

import com.keepit.common.akka.SafeFuture
import com.keepit.common.crypto.{ PublicIdConfiguration }
import com.keepit.search.controllers.util.{ SearchControllerUtil }
import com.keepit.search.engine.SearchFactory
import com.keepit.search.engine.uri.UriSearchResult
import com.keepit.search.index.Searcher
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
import com.keepit.search.augmentation.{ AugmentedItem, AugmentationCommander }
import com.keepit.social.BasicUser

object WebsiteSearchController {
  private[WebsiteSearchController] val maxKeepersShown = 20
  private[WebsiteSearchController] val maxLibrariesShown = 10
  private[WebsiteSearchController] val maxTagsShown = 15
}

class WebsiteSearchController @Inject() (
    val userActionsHelper: UserActionsHelper,
    val shoeboxClient: ShoeboxServiceClient,
    augmentationCommander: AugmentationCommander,
    libraryIndexer: LibraryIndexer,
    uriSearchCommander: UriSearchCommander,
    librarySearchCommander: LibrarySearchCommander,
    userSearchCommander: UserSearchCommander,
    searchFactory: SearchFactory,
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

    uriSearchCommander.searchUris(userId, acceptLangs, experiments, query, filter, libraryContextFuture, maxHits, lastUUIDStr, context, None, debugOpt).flatMap { uriSearchResult =>

      getWebsiteUriSearchResults(userId, uriSearchResult).imap {
        case (hits, users, libraries) =>
          val librariesJson = libraries.map { library =>
            Json.obj("id" -> library.id, "name" -> library.name, "color" -> library.color, "path" -> library.path, "visibility" -> library.visibility)
          }
          val result = Json.obj(
            "uuid" -> uriSearchResult.uuid,
            "context" -> IdFilterCompressor.fromSetToBase64(uriSearchResult.idFilter),
            "experimentId" -> uriSearchResult.searchExperimentId,
            "mayHaveMore" -> uriSearchResult.mayHaveMoreHits,
            "myTotal" -> uriSearchResult.myTotal,
            "friendsTotal" -> uriSearchResult.friendsTotal,
            "othersTotal" -> uriSearchResult.othersTotal,
            "hits" -> hits,
            "libraries" -> librariesJson,
            "users" -> users
          )
          Ok(result)
      }
    }
  }

  private def getWebsiteUriSearchResults(userId: Id[User], uriSearchResult: UriSearchResult): Future[(Seq[JsValue], Seq[BasicUser], Seq[BasicLibrary])] = {
    if (uriSearchResult.hits.isEmpty) {
      Future.successful((Seq.empty[JsObject], Seq.empty[BasicUser], Seq.empty[BasicLibrary]))
    } else {

      val uriIds = uriSearchResult.hits.map(hit => Id[NormalizedURI](hit.id))
      val futureUriSummaries = shoeboxClient.getUriSummaries(uriIds)
      val (futureBasicKeeps, futureLibrariesWithWriteAccess) = {
        if (userId == SearchControllerUtil.nonUser) {
          (Future.successful(Map.empty[Id[NormalizedURI], Set[BasicKeep]].withDefaultValue(Set.empty)), Future.successful(Set.empty[Id[Library]]))
        } else {
          (shoeboxClient.getBasicKeeps(userId, uriIds.toSet), shoeboxClient.getLibrariesWithWriteAccess(userId))
        }
      }

      getAugmentedItems(augmentationCommander)(userId, uriSearchResult).flatMap { augmentedItems =>
        val librarySearcher = libraryIndexer.getSearcher
        val (futureAugmentationFields, futureBasicUsersAndLibraries) = writesAugmentationFields(librarySearcher, futureBasicKeeps, futureLibrariesWithWriteAccess, userId, maxKeepersShown, maxLibrariesShown, maxTagsShown, augmentedItems)

        val futureJsHits = for {
          summaries <- futureUriSummaries
          allAugmentationFields <- futureAugmentationFields
        } yield {
          (uriSearchResult.hits zip allAugmentationFields).map {
            case (hit, augmentationFields) => {
              val uriId = Id[NormalizedURI](hit.id)
              val primaryFields = Json.obj(
                "title" -> hit.title,
                "url" -> hit.url,
                "score" -> hit.finalScore,
                "summary" -> summaries(uriId)
              )
              primaryFields ++ augmentationFields
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
  }

  private def writesAugmentationFields(
    librarySearcher: Searcher,
    futureBasicKeeps: Future[Map[Id[NormalizedURI], Set[BasicKeep]]],
    futureLibrariesWithWriteAccess: Future[Set[Id[Library]]],
    userId: Id[User],
    maxKeepersShown: Int,
    maxLibrariesShown: Int,
    maxTagsShown: Int,
    augmentedItems: Seq[AugmentedItem]): (Future[Seq[JsObject]], Future[(Seq[BasicUser], Seq[BasicLibrary])]) = {

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

    val futureAugmentationFields = for {
      basicKeeps <- futureBasicKeeps
      librariesWithWriteAccess <- futureLibrariesWithWriteAccess
    } yield {
      val allBasicKeeps = augmentedItems.map(item => basicKeeps.getOrElse(item.uri, Set.empty))
      (limitedAugmentationInfos zip allBasicKeeps).map {
        case (limitedInfo, keeps) =>

          def doShowKeeper(keeperId: Id[User]): Boolean = { keeperId != userId || keeps.nonEmpty } // ensuring consistency of keepers shown with the user's latest database data (race condition)
          val keepersIndices = limitedInfo.keepers.collect { case keeperId if doShowKeeper(keeperId) => userIndexById(keeperId) }

          def doShowLibrary(libraryId: Id[Library]): Boolean = { // ensuring consistency of libraries shown with the user's latest database data (race condition)
            lazy val publicId = Library.publicId(libraryId)
            libraryIndexById.contains(libraryId) && (!librariesWithWriteAccess.contains(libraryId) || keeps.exists(_.libraryId == publicId))
          }
          val librariesIndices = limitedInfo.libraries.collect {
            case (libraryId, keeperId) if doShowLibrary(libraryId) => Seq(libraryIndexById(libraryId), userIndexById(keeperId))
          }.flatten

          Json.obj(
            "keeps" -> keeps,
            "keepers" -> keepersIndices,
            "keepersOmitted" -> limitedInfo.keepersOmitted,
            "keepersTotal" -> limitedInfo.keepersTotal,
            "libraries" -> librariesIndices,
            "librariesOmitted" -> limitedInfo.librariesOmitted,
            "librariesTotal" -> limitedInfo.librariesTotal,
            "tags" -> limitedInfo.tags,
            "tagsOmitted" -> limitedInfo.tagsOmitted
          )
      }
    }

    (futureAugmentationFields, futureBasicUsersAndLibraries)
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
    doPrefixSearch: Boolean,
    debug: Option[String]) = UserAction.async { request =>

    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)
    val debugOpt = if (debug.isDefined && experiments.contains(ADMIN)) debug else None // debug is only for admin

    // Uri Search

    val futureUriSearchResultJson = if (maxUris <= 0) Future.successful(JsNull) else {
      uriSearchCommander.searchUris(userId, acceptLangs, experiments, query, filter, Future.successful(LibraryContext.None), maxUris, lastUUIDStr, uriContext, None, debugOpt).flatMap { uriSearchResult =>
        getWebsiteUriSearchResults(userId, uriSearchResult).imap {
          case (hits, users, libraries) =>
            val librariesJson = libraries.map { library =>
              Json.obj("id" -> library.id, "name" -> library.name, "color" -> library.color, "path" -> library.path, "visibility" -> library.visibility)
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

    val futureLibrarySearchResultJson = if (maxLibraries <= 0) Future.successful(JsNull) else {
      librarySearchCommander.searchLibraries(userId, acceptLangs, experiments, query, filter, libraryContext, maxLibraries, doPrefixSearch, None, debugOpt, None).flatMap { librarySearchResult =>
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
                  "color" -> library.color,
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

    val futureUserSearchResultJson = if (maxUsers <= 0) Future.successful(JsNull) else {
      SafeFuture { userSearchCommander.searchUsers(Some(userId), query, maxUsers, userContext, filter, excludeSelf = true) }.flatMap { userResult =>
        val futureMutualFriendsByUser = searchFactory.getMutualFriends(userId, userResult.hits.map(_.id).toSet)
        val publishedLibrariesCountByUser = {
          val librarySearcher = libraryIndexer.getSearcher
          userResult.hits.map { hit => hit.id -> LibraryIndexable.countPublishedLibrariesByMember(librarySearcher, hit.id) }.toMap
        }
        futureMutualFriendsByUser.map { mutualFriendsByUser =>
          Json.obj(
            "context" -> userResult.context,
            "hits" -> JsArray(userResult.hits.map { hit =>
              Json.obj(
                "id" -> hit.basicUser.externalId,
                "name" -> hit.basicUser.fullName,
                "username" -> hit.basicUser.username.value,
                "pictureName" -> hit.basicUser.pictureName,
                "mutualFriendCount" -> mutualFriendsByUser(hit.id).size,
                "libraryCount" -> publishedLibrariesCountByUser(hit.id)
              //"keepCount" todo(Léo): define valid keep count definition
              )
            })
          )
        }
      }
    }

    for {
      uriSearchResultJson <- futureUriSearchResultJson
      librarySearchResultJson <- futureLibrarySearchResultJson
      userSearchResultJson <- futureUserSearchResultJson
    } yield {
      val json = Json.obj(
        "query" -> query,
        "uris" -> uriSearchResultJson,
        "libraries" -> librarySearchResultJson,
        "users" -> userSearchResultJson
      )
      Ok(json)
    }
  }
}

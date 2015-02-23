package com.keepit.search.controllers.mobile

import com.keepit.commanders.ProcessedImageSize
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.search.augmentation.AugmentationCommander
import com.keepit.search.engine.SearchFactory
import com.keepit.search.engine.uri.UriSearchResult
import com.keepit.social.BasicUser
import play.api.libs.json._
import com.google.inject.Inject
import com.keepit.common.controller.{ SearchServiceController, UserActions, UserActionsHelper }
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.search.util.IdFilterCompressor
import com.keepit.search.{ UserSearchCommander, LibraryContext, LibrarySearchCommander, UriSearchCommander }
import com.keepit.model.ExperimentType._
import play.api.libs.json.JsArray
import com.keepit.search.index.graph.library.{ LibraryIndexer, LibraryIndexable }
import com.keepit.search.controllers.util.SearchControllerUtil
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.core._

import scala.concurrent.Future

import com.keepit.search.controllers.mobile.MobileSearchController._

object MobileSearchController {
  private[MobileSearchController] val maxKeepersShown = 20
  private[MobileSearchController] val maxLibrariesShown = 0
  private[MobileSearchController] val maxTagsShown = 0
}

class MobileSearchController @Inject() (
    val userActionsHelper: UserActionsHelper,
    implicit val publicIdConfig: PublicIdConfiguration,
    val shoeboxClient: ShoeboxServiceClient,
    uriSearchCommander: UriSearchCommander,
    librarySearchCommander: LibrarySearchCommander,
    userSearchCommander: UserSearchCommander,
    augmentationCommander: AugmentationCommander,
    searchFactory: SearchFactory,
    libraryIndexer: LibraryIndexer) extends UserActions with SearchServiceController with SearchControllerUtil with Logging {

  def searchV1(
    query: String,
    filter: Option[String],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
    withUriSummary: Boolean = false) = UserAction { request =>

    val userId = request.userId
    val acceptLangs: Seq[String] = request.request.acceptLanguages.map(_.code)

    val decoratedResult = uriSearchCommander.search(userId, acceptLangs, request.experiments, query, filter, maxHits, lastUUIDStr, context, predefinedConfig = None, None, withUriSummary)

    Ok(toKifiSearchResultV1(decoratedResult, sanitize = true)).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  def warmUp() = UserAction { request =>
    uriSearchCommander.warmUp(request.userId)
    Ok
  }

  def searchLibrariesV1(
    query: String,
    filter: Option[String],
    maxHits: Int,
    context: Option[String],
    debug: Option[String]) = UserAction.async { request =>

    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)

    val debugOpt = if (debug.isDefined && experiments.contains(ADMIN)) debug else None // debug is only for admin

    librarySearchCommander.searchLibraries(userId, acceptLangs, experiments, query, filter, context, maxHits, true, None, debugOpt, None).flatMap { librarySearchResult =>
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

  def searchV2(
    query: String,
    filter: Option[String],
    maxUris: Int,
    uriContext: Option[String],
    lastUUIDStr: Option[String],
    maxLibraries: Int,
    libraryContext: Option[String],
    maxUsers: Int,
    userContext: Option[String],
    disablePrefixSearch: Boolean,
    debug: Option[String]) = UserAction.async { request =>

    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)
    val debugOpt = if (debug.isDefined && experiments.contains(ADMIN)) debug else None // debug is only for admin

    // Uri Search

    val futureUriSearchResultJson = if (maxUris <= 0) Future.successful(JsNull) else {
      uriSearchCommander.searchUris(userId, acceptLangs, experiments, query, filter, Future.successful(LibraryContext.None), maxUris, lastUUIDStr, uriContext, None, debugOpt).flatMap { uriSearchResult =>
        getMobileUriSearchResults(userId, uriSearchResult).imap {
          case (jsHits, users) =>
            Json.obj(
              "uuid" -> uriSearchResult.uuid,
              "context" -> IdFilterCompressor.fromSetToBase64(uriSearchResult.idFilter),
              "mayHaveMore" -> uriSearchResult.mayHaveMoreHits,
              "myTotal" -> uriSearchResult.myTotal,
              "friendsTotal" -> uriSearchResult.friendsTotal,
              "othersTotal" -> uriSearchResult.othersTotal,
              "hits" -> jsHits,
              "keepers" -> users
            )
        }
      }
    }

    // Library Search

    val futureLibrarySearchResultJson = if (maxLibraries <= 0) Future.successful(JsNull) else {
      librarySearchCommander.searchLibraries(userId, acceptLangs, experiments, query, filter, libraryContext, maxLibraries, disablePrefixSearch, None, debugOpt, None).flatMap { librarySearchResult =>
        val librarySearcher = libraryIndexer.getSearcher
        val libraryRecordsAndVisibilityById = getLibraryRecordsAndVisibility(librarySearcher, librarySearchResult.hits.map(_.id).toSet)
        val futureUsers = shoeboxClient.getBasicUsers(libraryRecordsAndVisibilityById.values.map(_._1.ownerId).toSeq.distinct)
        val futureLibraryStatistics = shoeboxClient.getBasicLibraryStatistics(libraryRecordsAndVisibilityById.keySet)
        val futureLibraryImages = shoeboxClient.getLibraryImageUrls(libraryRecordsAndVisibilityById.keySet, ProcessedImageSize.Medium.idealSize) // todo(Léo): Ask for square image
        for {
          usersById <- futureUsers
          libraryStatisticsById <- futureLibraryStatistics
          libraryImagesById <- futureLibraryImages
        } yield {
          val hitsArray = JsArray(librarySearchResult.hits.flatMap { hit =>
            libraryRecordsAndVisibilityById.get(hit.id).map {
              case (library, visibility) =>
                val owner = usersById(library.ownerId)
                val path = Library.formatLibraryPath(owner.username, library.slug)
                val statistics = libraryStatisticsById(library.id)
                val description = library.description.getOrElse("")
                val imageUrl = libraryImagesById.get(library.id)
                Json.obj(
                  "id" -> Library.publicId(hit.id),
                  "score" -> hit.score,
                  "name" -> library.name,
                  "description" -> description,
                  "color" -> library.color,
                  "imageUrl" -> imageUrl,
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
      userSearchCommander.searchUsers(userId, acceptLangs, experiments, query, filter, userContext, maxUsers, disablePrefixSearch, None, debugOpt, None).flatMap { userSearchResult =>
        val userIds = userSearchResult.hits.map(_.id).toSet
        val futureUsers = shoeboxClient.getBasicUsers(userIds.toSeq)
        val futureFriends = searchFactory.getFriends(userId)
        val futureMutualFriendsByUser = searchFactory.getMutualFriends(userId, userIds)
        val futureKeepCountsByUser = shoeboxClient.getKeepCounts(userIds)
        val librarySearcher = libraryIndexer.getSearcher
        val publishedLibrariesCountByMember = userSearchResult.hits.map { hit => hit.id -> LibraryIndexable.countPublishedLibrariesByMember(librarySearcher, hit.id) }.toMap
        val publishedLibrariesCountByOwner = userSearchResult.hits.map { hit => hit.id -> LibraryIndexable.countPublishedLibrariesByOwner(librarySearcher, hit.id) }.toMap
        val relevantLibraryRecordsAndVisibity = getLibraryRecordsAndVisibility(librarySearcher, userSearchResult.hits.flatMap(_.library).toSet)
        for {
          keepCountsByUser <- futureKeepCountsByUser
          mutualFriendsByUser <- futureMutualFriendsByUser
          friends <- futureFriends
          users <- futureUsers
        } yield {
          Json.obj(
            "context" -> IdFilterCompressor.fromSetToBase64(userSearchResult.idFilter),
            "hits" -> JsArray(userSearchResult.hits.map { hit =>
              val user = users(hit.id)
              val relevantLibrary = hit.library.flatMap { libraryId =>
                relevantLibraryRecordsAndVisibity.get(libraryId).map {
                  case (record, visibility) =>
                    require(record.ownerId == hit.id, "Relevant library owner doesn't match returned user.")
                    val library = makeBasicLibrary(record, visibility, user)
                    Json.obj("id" -> library.id, "name" -> library.name, "color" -> library.color, "path" -> library.path, "visibility" -> library.visibility)
                }
              }
              Json.obj(
                "id" -> user.externalId,
                "name" -> user.fullName,
                "username" -> user.username.value,
                "pictureName" -> user.pictureName,
                "isFriend" -> friends.contains(hit.id.id),
                "mutualFriendCount" -> mutualFriendsByUser(hit.id).size,
                "libraryCount" -> publishedLibrariesCountByOwner(hit.id),
                "libraryMembershipCount" -> publishedLibrariesCountByMember(hit.id),
                "keepCount" -> keepCountsByUser(hit.id),
                "relevantLibrary" -> relevantLibrary
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

  private def getMobileUriSearchResults(userId: Id[User], uriSearchResult: UriSearchResult): Future[(Seq[JsValue], Seq[BasicUser])] = {
    if (uriSearchResult.hits.isEmpty) {
      Future.successful((Seq.empty[JsObject], Seq.empty[BasicUser]))
    } else {

      val uriIds = uriSearchResult.hits.map(hit => Id[NormalizedURI](hit.id))
      val futureUriSummaries = shoeboxClient.getUriSummaries(uriIds)

      getAugmentedItems(augmentationCommander)(userId, uriSearchResult).flatMap { augmentedItems =>
        val limitedAugmentationInfos = augmentedItems.map(_.toLimitedAugmentationInfo(maxKeepersShown, maxLibrariesShown, maxTagsShown))
        val allKeepersShown = limitedAugmentationInfos.map(_.keepers)

        val futureUsers = {
          val uniqueKeepersShown = allKeepersShown.flatten.distinct
          shoeboxClient.getBasicUsers(uniqueKeepersShown)
        }

        for {
          summaries <- futureUriSummaries
          users <- futureUsers
        } yield {
          val jsHits = (uriSearchResult.hits zip limitedAugmentationInfos).map {
            case (hit, limitedInfo) => {
              val uriId = Id[NormalizedURI](hit.id)
              Json.obj(
                "title" -> hit.title,
                "url" -> hit.url,
                "score" -> hit.finalScore,
                "summary" -> summaries(uriId),
                "keepers" -> limitedInfo.keepers.map(users(_).externalId),
                "keepersOmitted" -> limitedInfo.keepersOmitted,
                "keepersTotal" -> limitedInfo.keepersTotal
              )
            }
          }
          (jsHits, users.values.toSeq)
        }
      }
    }
  }
}

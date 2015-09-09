package com.keepit.search.controllers.mobile

import com.keepit.commanders.ProcessedImageSize
import com.keepit.common.db.Id
import com.keepit.common.json
import com.keepit.common.store.{ ImageSize, S3ImageConfig }
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.model.BasicImages
import com.keepit.search.augmentation.AugmentationCommander
import com.keepit.search.engine.SearchFactory
import com.keepit.search.engine.uri.UriSearchResult
import com.keepit.search.index.graph.library.membership.{ LibraryMembershipIndexable, LibraryMembershipIndexer }
import com.keepit.social.BasicUser
import play.api.libs.json._
import com.google.inject.Inject
import com.keepit.common.controller.{ SearchServiceController, UserActions, UserActionsHelper }
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.search.util.IdFilterCompressor
import com.keepit.search._
import com.keepit.model.UserExperimentType._
import play.api.libs.json.JsArray
import com.keepit.search.index.graph.library.{ LibraryIndexer }
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
    implicit val imageConfig: S3ImageConfig,
    val shoeboxClient: ShoeboxServiceClient,
    rover: RoverServiceClient,
    uriSearchCommander: UriSearchCommander,
    librarySearchCommander: LibrarySearchCommander,
    userSearchCommander: UserSearchCommander,
    augmentationCommander: AugmentationCommander,
    searchFactory: SearchFactory,
    libraryIndexer: LibraryIndexer,
    libraryMembershipIndexer: LibraryMembershipIndexer) extends UserActions with SearchServiceController with SearchControllerUtil with Logging {

  def warmUp() = UserAction { request =>
    uriSearchCommander.warmUp(request.userId)
    Ok
  }

  def searchV2(
    query: String,
    proximityFilter: Option[String],
    libraryFilter: Option[String],
    userFilter: Option[String],
    organizationFilter: Option[String],
    maxUris: Int,
    uriContext: Option[String],
    lastUUIDStr: Option[String],
    maxLibraries: Int,
    libraryContext: Option[String],
    maxUsers: Int,
    userContext: Option[String],
    disablePrefixSearch: Boolean,
    disableFullTextSearch: Boolean,
    orderBy: Option[String],
    libraryAuth: Option[String],
    idealImageSize: Option[ImageSize],
    debug: Option[String]) = UserAction.async { request =>

    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)
    val debugOpt = if (debug.isDefined && experiments.contains(ADMIN)) debug else None // debug is only for admin

    val libraryScopeFuture = getLibraryScope(libraryFilter, request.userIdOpt, libraryAuth)
    val userScopeFuture = getUserScope(userFilter)
    val organizationScopeFuture = getOrganizationScope(organizationFilter, request.userIdOpt)
    val proximityScope = getProximityScope(proximityFilter)
    val searchFilterFuture = makeSearchFilter(proximityScope, libraryScopeFuture, userScopeFuture, organizationScopeFuture)

    val parsedOrderBy = orderBy.flatMap(SearchRanking.parse) getOrElse {
      if (query.contains("tag:")) SearchRanking.recency else SearchRanking.default // todo(Léo): remove this backward compatibility hack, this should be explicitly supplied by client
    }

    // Uri Search

    val futureUriSearchResultJson = if (maxUris <= 0) Future.successful(JsNull) else {
      val uriSearchContextFuture = searchFilterFuture.map { filter => SearchContext(uriContext, parsedOrderBy, filter, disablePrefixSearch, disableFullTextSearch) }
      uriSearchCommander.searchUris(userId, acceptLangs, experiments, query, uriSearchContextFuture, maxUris, lastUUIDStr, None, debugOpt).flatMap { uriSearchResult =>
        getMobileUriSearchResults(userId, uriSearchResult, idealImageSize).imap {
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
      val librarySearchContextFuture = searchFilterFuture.map { filter => SearchContext(libraryContext, parsedOrderBy, filter, disablePrefixSearch, disableFullTextSearch) }
      librarySearchCommander.searchLibraries(userId, acceptLangs, experiments, query, librarySearchContextFuture, maxLibraries, None, debugOpt, None).flatMap { librarySearchResult =>
        val librarySearcher = libraryIndexer.getSearcher
        val libraryRecordsAndVisibilityById = getLibraryRecordsAndVisibilityAndKind(librarySearcher, librarySearchResult.hits.map(_.id).toSet)
        val futureLibraryDetails = shoeboxClient.getBasicLibraryDetails(libraryRecordsAndVisibilityById.keySet, idealImageSize getOrElse ProcessedImageSize.Medium.idealSize, Some(userId))
        val futureUsers = shoeboxClient.getBasicUsers(libraryRecordsAndVisibilityById.values.map(_._1.ownerId).toSeq.distinct)
        val futureOrganizations = shoeboxClient.getBasicOrganizationsByIds(libraryRecordsAndVisibilityById.values.flatMap(_._1.orgId).toSet)

        for {
          usersById <- futureUsers
          organizationsById <- futureOrganizations
          libraryDetails <- futureLibraryDetails
        } yield {
          val hitsArray = JsArray(librarySearchResult.hits.flatMap { hit =>
            libraryRecordsAndVisibilityById.get(hit.id).map {
              case (library, visibility, _) =>
                val owner = usersById(library.ownerId)
                val organization = library.orgId.map(organizationsById(_))
                val details = libraryDetails(library.id)
                val path = LibraryPathHelper.formatLibraryPath(owner, organization.map(_.handle), details.slug)

                val description = library.description.orElse(details.description).getOrElse("")

                Json.obj(
                  "id" -> Library.publicId(hit.id),
                  "score" -> hit.score,
                  "name" -> library.name,
                  "description" -> description,
                  "color" -> library.color,
                  "imageUrl" -> details.imageUrl,
                  "path" -> path,
                  "visibility" -> visibility,
                  "owner" -> owner,
                  "org" -> organization,
                  "memberCount" -> (details.numFollowers + details.numCollaborators),
                  "numFollowers" -> details.numFollowers,
                  "numCollaborators" -> details.numCollaborators,
                  "keepCount" -> details.keepCount
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
      val userSearchContextFuture = searchFilterFuture.map { filter => SearchContext(userContext, parsedOrderBy, filter, disablePrefixSearch, disableFullTextSearch) }
      userSearchCommander.searchUsers(userId, acceptLangs, experiments, query, userSearchContextFuture, maxUsers, None, debugOpt, None).flatMap { userSearchResult =>
        val futureFriends = searchFactory.getFriends(userId)
        val userIds = userSearchResult.hits.map(_.id).toSet
        val futureMutualFriendsByUser = searchFactory.getMutualFriends(userId, userIds)
        val futureKeepCountsByUser = shoeboxClient.getKeepCounts(userIds)
        val librarySearcher = libraryIndexer.getSearcher
        val relevantLibraryRecordsAndVisibility = getLibraryRecordsAndVisibilityAndKind(librarySearcher, userSearchResult.hits.flatMap(_.library).toSet)
        val futureUsers = {
          val libraryOwnerIds = relevantLibraryRecordsAndVisibility.values.map(_._1.ownerId)
          shoeboxClient.getBasicUsers((userIds ++ libraryOwnerIds).toSeq)
        }
        val libraryMembershipSearcher = libraryMembershipIndexer.getSearcher
        val publishedLibrariesCountByMember = userSearchResult.hits.map { hit => hit.id -> LibraryMembershipIndexable.countPublishedLibrariesByMember(librarySearcher, libraryMembershipSearcher, hit.id) }.toMap
        val publishedLibrariesCountByCollaborator = userSearchResult.hits.map { hit => hit.id -> LibraryMembershipIndexable.countPublishedLibrariesByCollaborator(librarySearcher, libraryMembershipSearcher, hit.id) }.toMap
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
                relevantLibraryRecordsAndVisibility.get(libraryId).map {
                  case (record, visibility, _) =>
                    val owner = users(record.ownerId)
                    val library = makeBasicLibrary(record, visibility, owner, None) // todo: after orgId is indexed into LibraryRecord, we can call shoebox and get orgInfo
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
                "libraryCount" -> publishedLibrariesCountByCollaborator(hit.id),
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

  private def getMobileUriSearchResults(userId: Id[User], uriSearchResult: UriSearchResult, idealImageSize: Option[ImageSize]): Future[(Seq[JsValue], Seq[BasicUser])] = {
    if (uriSearchResult.hits.isEmpty) {
      Future.successful((Seq.empty[JsObject], Seq.empty[BasicUser]))
    } else {

      val uriIds = uriSearchResult.hits.map(hit => Id[NormalizedURI](hit.id))
      val futureUriSummaries = rover.getUriSummaryByUris(uriIds.toSet)
      val futureKeepImages: Future[Map[Id[Keep], BasicImages]] = {
        val keepIds = uriSearchResult.hits.flatMap(hit => hit.keepId.map(Id[Keep](_)))
        shoeboxClient.getKeepImages(keepIds.toSet)
      }

      getAugmentedItems(augmentationCommander)(userId, uriSearchResult).flatMap { augmentedItems =>
        val limitedAugmentationInfos = augmentedItems.map(_.toLimitedAugmentationInfo(maxKeepersShown, maxLibrariesShown, maxTagsShown))
        val allKeepersShown = limitedAugmentationInfos.map(_.keepers)

        val futureUsers = {
          val uniqueKeepersShown = allKeepersShown.flatMap(_.map(_._1)).distinct
          shoeboxClient.getBasicUsers(uniqueKeepersShown)
        }

        for {
          summaries <- futureUriSummaries
          keepImages <- futureKeepImages
          users <- futureUsers
        } yield {
          val jsHits = (uriSearchResult.hits zip limitedAugmentationInfos).map {
            case (hit, limitedInfo) => {
              val uriId = Id[NormalizedURI](hit.id)
              val summary = summaries.get(uriId)
              val keepId = hit.keepId.map(Id[Keep](_))
              val image = (keepId.flatMap(keepImages.get) orElse summary.map(_.images)).flatMap(_.get(idealImageSize.getOrElse(ProcessedImageSize.Medium.idealSize)))
              Json.obj(
                "title" -> hit.title,
                "url" -> hit.url,
                "score" -> hit.finalScore,
                "summary" -> json.aggressiveMinify(Json.obj(
                  "title" -> summary.flatMap(_.article.title),
                  "description" -> summary.flatMap(_.article.description),
                  "imageUrl" -> image.map(_.path.getUrl),
                  "imageWidth" -> image.map(_.size.width),
                  "imageHeight" -> image.map(_.size.height)
                )),
                "keepers" -> limitedInfo.keepers.map { case (keeperId, _) => users(keeperId).externalId },
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

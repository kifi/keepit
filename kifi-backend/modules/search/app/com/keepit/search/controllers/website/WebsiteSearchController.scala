package com.keepit.search.controllers.website

import com.keepit.commanders.ProcessedImageSize
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.store.{ ImageSize, S3ImageConfig }
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.model.{ BasicImages, RoverUriSummary }
import com.keepit.search.controllers.util.{ SearchControllerUtil }
import com.keepit.search.engine.SearchFactory
import com.keepit.search.engine.uri.UriSearchResult
import com.keepit.search.index.Searcher
import com.keepit.search.index.graph.library.membership.{ LibraryMembershipIndexer, LibraryMembershipIndexable }
import com.keepit.shoebox.ShoeboxServiceClient
import com.google.inject.Inject
import com.keepit.common.controller._
import com.keepit.common.logging.Logging
import com.keepit.model.ExperimentType.ADMIN
import com.keepit.search._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import play.api.libs.json._
import com.keepit.search.index.graph.library.{ LibraryIndexer }
import com.keepit.model._
import com.keepit.search.util.IdFilterCompressor
import com.keepit.common.db.{ Id }
import com.keepit.common.core._
import com.keepit.search.controllers.website.WebsiteSearchController._
import com.keepit.search.augmentation.{ RestrictedKeepInfo, AugmentedItem, AugmentationCommander }
import com.keepit.social.BasicUser
import com.keepit.common.json

object WebsiteSearchController {
  private[WebsiteSearchController] val maxKeepersShown = 20
  private[WebsiteSearchController] val maxLibrariesShown = 10
  private[WebsiteSearchController] val maxTagsShown = 15

  private[WebsiteSearchController] val maxCollaboratorsShown = 5
  private[WebsiteSearchController] val maxFollowersShown = 5
}

class WebsiteSearchController @Inject() (
    val userActionsHelper: UserActionsHelper,
    val shoeboxClient: ShoeboxServiceClient,
    augmentationCommander: AugmentationCommander,
    libraryIndexer: LibraryIndexer,
    libraryMembershipIndexer: LibraryMembershipIndexer,
    uriSearchCommander: UriSearchCommander,
    librarySearchCommander: LibrarySearchCommander,
    userSearchCommander: UserSearchCommander,
    searchFactory: SearchFactory,
    rover: RoverServiceClient,
    implicit val imageConfig: S3ImageConfig,
    implicit val publicIdConfig: PublicIdConfiguration,
    airbrake: AirbrakeNotifier) extends UserActions with SearchServiceController with SearchControllerUtil with Logging {

  def search2(
    query: String,
    userFilter: Option[String],
    libraryFilter: Option[String],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
    auth: Option[String],
    debug: Option[String] = None) = MaybeUserAction.async { request =>

    val libraryContextFuture = getLibraryFilterFuture(libraryFilter.map(PublicId[Library](_)), auth, request)
    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)
    val filterFuture = getUserFilterFuture(userFilter)

    val orderBy = SearchRanking.default

    val debugOpt = if (debug.isDefined && experiments.contains(ADMIN)) debug else None // debug is only for admin

    uriSearchCommander.searchUris(userId, acceptLangs, experiments, query, filterFuture, libraryContextFuture, orderBy, maxHits, lastUUIDStr, context, None, debugOpt).flatMap { uriSearchResult =>

      getWebsiteUriSearchResults(userId, uriSearchResult, None).imap {
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

  private def getWebsiteUriSearchResults(userId: Id[User], uriSearchResult: UriSearchResult, idealImageSize: Option[ImageSize]): Future[(Seq[JsValue], Seq[BasicUser], Seq[BasicLibrary])] = {
    if (uriSearchResult.hits.isEmpty) {
      Future.successful((Seq.empty[JsObject], Seq.empty[BasicUser], Seq.empty[BasicLibrary]))
    } else {
      val uriIds = uriSearchResult.hits.map(hit => Id[NormalizedURI](hit.id))
      val futureUriSummaries: Future[Map[Id[NormalizedURI], RoverUriSummary]] = rover.getUriSummaryByUris(uriIds.toSet)
      val futureKeepImages: Future[Map[Id[Keep], BasicImages]] = {
        val keepIds = uriSearchResult.hits.flatMap(hit => hit.keepId.map(Id[Keep](_)))
        shoeboxClient.getKeepImages(keepIds.toSet)
      }

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
          keepImages <- futureKeepImages
          allAugmentationFields <- futureAugmentationFields
        } yield {
          (uriSearchResult.hits zip allAugmentationFields).map {
            case (hit, augmentationFields) => {
              val uriId = Id[NormalizedURI](hit.id)
              val title = hit.title
              val url = hit.url
              val siteName = DomainToNameMapper.getNameFromUrl(url)
              val summary = summaries.get(uriId)
              val keepId = hit.keepId.map(Id[Keep](_))
              val imageOpt = (keepId.flatMap(keepImages.get) orElse summary.map(_.images)).flatMap(_.get(idealImageSize.getOrElse(ProcessedImageSize.Medium.idealSize)))
              val primaryFields = Json.obj(
                "title" -> title,
                "description" -> summary.flatMap(_.article.description),
                "wordCount" -> summary.flatMap(_.article.wordCount),
                "url" -> url,
                "siteName" -> siteName,
                "image" -> imageOpt.map { image =>
                  Json.obj(
                    "url" -> image.path.getUrl,
                    "width" -> image.size.width,
                    "height" -> image.size.height
                  )
                },
                "score" -> hit.finalScore,
                "summary" -> json.minify(Json.obj( // todo(Léo): remove deprecated summary field
                  "title" -> summary.flatMap(_.article.title),
                  "description" -> summary.flatMap(_.article.description),
                  "imageUrl" -> imageOpt.map(_.path.getUrl),
                  "imageWidth" -> imageOpt.map(_.size.width),
                  "imageHeight" -> imageOpt.map(_.size.height)
                ))
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
    val allKeepsShown = limitedAugmentationInfos.map(_.keep).flatten
    val allKeepersShown = limitedAugmentationInfos.map(_.keepers).flatten
    val allLibrariesShown = limitedAugmentationInfos.map(_.libraries).flatten

    val userIds = ((allKeepsShown.flatMap(_.keptBy) ++ allKeepersShown.map(_._1) ++ allLibrariesShown.map(_._2)).toSet - userId).toSeq
    val userIndexById = userIds.zipWithIndex.toMap + (userId -> -1)

    val libraryRecordsAndVisibilityById = getLibraryRecordsAndVisibilityAndKind(librarySearcher, (allKeepsShown.flatMap(_.keptIn) ++ allLibrariesShown.map(_._1)).toSet)

    val libraryIds = libraryRecordsAndVisibilityById.keys.toSeq // libraries that are missing from the index are implicitly dropped here (race condition)
    val libraryIndexById = libraryIds.zipWithIndex.toMap

    val futureBasicUsersAndLibraries = {
      val libraryOwnerIds = libraryRecordsAndVisibilityById.values.map(_._1.ownerId)
      shoeboxClient.getBasicUsers(userIds ++ libraryOwnerIds).map { usersById =>
        val users = userIds.map(usersById(_))
        val libraries = libraryIds.map { libId =>
          val (library, visibility, _) = libraryRecordsAndVisibilityById(libId)
          val owner = usersById(library.ownerId)
          makeBasicLibrary(library, visibility, owner, None) // todo: after orgId is indexed into LibraryRecord, we can call shoebox and get orgInfo
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
          val keepersIndices = limitedInfo.keepers.collect { case (keeperId, _) if doShowKeeper(keeperId) => userIndexById(keeperId) }

          def doShowLibrary(libraryId: Id[Library]): Boolean = { // ensuring consistency of libraries shown with the user's latest database data (race condition)
            lazy val publicId = Library.publicId(libraryId)
            libraryIndexById.contains(libraryId) && (!librariesWithWriteAccess.contains(libraryId) || keeps.exists(_.libraryId == publicId))
          }
          val librariesIndices = limitedInfo.libraries.collect {
            case (libraryId, keeperId, _) if doShowLibrary(libraryId) => Seq(libraryIndexById(libraryId), userIndexById(keeperId))
          }.flatten

          val (libraryIndex, keeperIndex, keptAt, note) = limitedInfo.keep match {
            case Some(RestrictedKeepInfo(_, keptAt, library, Some(keeperId), note, _)) if !library.exists(!doShowLibrary(_)) =>
              (library.map(libraryIndexById(_)), Some(userIndexById(keeperId)), Some(keptAt), note) // canonical keep
            case _ => limitedInfo.libraries.collectFirst {
              case (libraryId, keeperId, keptAt) if doShowLibrary(libraryId) =>
                (Some(libraryIndexById(libraryId)), Some(userIndexById(keeperId)), Some(keptAt), None) // first accessible keep
            } orElse {
              limitedInfo.keepers.headOption.map { case (keeperId, keptAt) => (None, Some(userIndexById(keeperId)), Some(keptAt), None) } // first discoverable keep
            } getOrElse (None, None, None, None)
          }

          Json.obj(
            "user" -> keeperIndex,
            "library" -> libraryIndex,
            "createdAt" -> keptAt, // field named createdAt for legacy reason
            "note" -> note,
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
    userFilter: Option[String],
    libraryFilter: Option[String],
    maxUris: Int,
    uriContext: Option[String],
    lastUUIDStr: Option[String],
    maxLibraries: Int,
    libraryContext: Option[String],
    maxUsers: Int,
    userContext: Option[String],
    disablePrefixSearch: Boolean,
    orderBy: Option[String],
    libraryAuth: Option[String],
    idealImageSize: Option[ImageSize],
    debug: Option[String]) = MaybeUserAction.async { request =>

    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)
    val debugOpt = if (debug.isDefined && experiments.contains(ADMIN)) debug else None // debug is only for admin
    val userFilterFuture = getUserFilterFuture(userFilter)
    val libraryFilterFuture = getLibraryFilterFuture(libraryFilter.map(PublicId[Library](_)), libraryAuth, request)

    val parsedOrderBy = orderBy.flatMap(SearchRanking.parse) getOrElse {
      if (query.contains("tag:")) SearchRanking.recency else SearchRanking.default
    }

    // Uri Search

    val futureUriSearchResultJson = if (maxUris <= 0) Future.successful(JsNull) else {
      uriSearchCommander.searchUris(userId, acceptLangs, experiments, query, userFilterFuture, libraryFilterFuture, parsedOrderBy, maxUris, lastUUIDStr, uriContext, None, debugOpt).flatMap { uriSearchResult =>
        getWebsiteUriSearchResults(userId, uriSearchResult, idealImageSize).imap {
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
              "keepers" -> users
            )
        }
      }
    }

    // Library Search

    val futureLibrarySearchResultJson = if (maxLibraries <= 0) Future.successful(JsNull) else {
      librarySearchCommander.searchLibraries(userId, acceptLangs, experiments, query, userFilterFuture, libraryContext, maxLibraries, disablePrefixSearch, None, debugOpt, None).flatMap { librarySearchResult =>
        val librarySearcher = libraryIndexer.getSearcher
        val libraryRecordsAndVisibilityById = getLibraryRecordsAndVisibilityAndKind(librarySearcher, librarySearchResult.hits.map(_.id).toSet)
        val libraryIds = libraryRecordsAndVisibilityById.keySet
        val futureLibraryDetails = shoeboxClient.getBasicLibraryDetails(libraryIds, idealImageSize.getOrElse(ProcessedImageSize.Medium.idealSize), Some(userId))

        val futureMembersShownByLibraryId = {
          val futureFriendIds = searchFactory.getFriends(userId)
          val libraryMembershipSearcher = libraryMembershipIndexer.getSearcher
          val membersByLibraryId = libraryIds.map { libraryId =>
            libraryId -> LibraryMembershipIndexable.getMembersByLibrary(libraryMembershipSearcher, libraryId)
          }.toMap

          futureFriendIds.map { allFriendIds =>
            def orderWithFriendsFirst(userIds: Set[Long]): Seq[Long] = {
              val (friends, others) = userIds.toSeq.partition(allFriendIds.contains)
              friends ++ others
            }
            membersByLibraryId.map {
              case (libraryId, (owners, collaborators, followers)) =>
                if (owners.size > 1) {
                  airbrake.notify(new IllegalStateException(s"Library $libraryId has more then one owner: $owners"))
                }
                val collaboratorsShown = orderWithFriendsFirst(collaborators).take(maxCollaboratorsShown).map(Id[User](_))
                val followersShown = orderWithFriendsFirst(followers).take(maxFollowersShown).map(Id[User](_))

                libraryId -> (collaboratorsShown, followersShown)
            }
          }
        }

        val futureUsers = futureMembersShownByLibraryId.flatMap { membersShownByLibraryId =>
          val userIds = libraryRecordsAndVisibilityById.values.map(_._1.ownerId).toSet ++ membersShownByLibraryId.values.flatMap {
            case (collaboratorsShown, followersShown) =>
              collaboratorsShown ++ followersShown
          }
          shoeboxClient.getBasicUsers(userIds.toSeq)
        }

        for {
          usersById <- futureUsers
          libraryDetailsById <- futureLibraryDetails
          libraryMembersById <- futureMembersShownByLibraryId
        } yield {
          def orderWithPictureFirst(users: Seq[BasicUser]): Seq[BasicUser] = {
            val (usersWithPics, usersNoPics) = users.partition(_.pictureName != "0.jpg")
            usersWithPics ++ usersNoPics
          }
          val hitsArray = JsArray(librarySearchResult.hits.flatMap { hit =>
            libraryRecordsAndVisibilityById.get(hit.id).map {
              case (library, visibility, kind) =>
                val owner = usersById(library.ownerId)
                val (collaboratorIds, followerIds) = libraryMembersById(hit.id)
                val collaborators = orderWithPictureFirst(collaboratorIds.map(usersById(_)))
                val followers = orderWithPictureFirst(followerIds.map(usersById(_)))
                val path = Library.formatLibraryPath(owner.username, None, library.slug) // todo: after orgId is indexed into LibraryRecord, we can call shoebox and get orgInfo
                val details = libraryDetailsById(library.id)
                val description = library.description.getOrElse("")
                val membershipInfo = details.membership.map(LibraryMembershipInfo.fromMembership)

                // todo(Léo): in a perfect world, this converges towards LibraryCardInfo
                Json.obj(
                  "id" -> Library.publicId(hit.id),
                  "score" -> hit.score,
                  "name" -> library.name,
                  "description" -> description,
                  "color" -> library.color,
                  "path" -> path,
                  "visibility" -> visibility,
                  "kind" -> kind,
                  "owner" -> owner,
                  "collaborators" -> collaborators,
                  "followers" -> followers,
                  "numFollowers" -> details.numFollowers,
                  "numCollaborators" -> details.numCollaborators,
                  "numKeeps" -> details.keepCount,
                  "membership" -> membershipInfo,
                  "memberCount" -> (details.numFollowers + details.numCollaborators), // deprecated
                  "keepCount" -> details.keepCount // deprecated,
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
      userSearchCommander.searchUsers(userId, acceptLangs, experiments, query, userFilter, userContext, maxUsers, disablePrefixSearch, None, debugOpt, None).flatMap { userSearchResult =>
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
                    Json.obj("id" -> library.id, "name" -> library.name, "description" -> record.description, "color" -> library.color, "path" -> library.path, "visibility" -> library.visibility)
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
}

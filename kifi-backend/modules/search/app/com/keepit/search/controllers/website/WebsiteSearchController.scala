package com.keepit.search.controllers.website

import com.keepit.commanders.ProcessedImageSize
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.domain.DomainToNameMapper
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.store.{ ImageSize, S3ImageConfig }
import com.keepit.common.util.IdFilterCompressor
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
import com.keepit.model.UserExperimentType.ADMIN
import com.keepit.search._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import play.api.libs.json._
import com.keepit.search.index.graph.library.{ LibraryIndexer }
import com.keepit.model._
import com.keepit.common.db.{ Id }
import com.keepit.common.core._
import com.keepit.search.controllers.website.WebsiteSearchController._
import com.keepit.search.augmentation.{ KeepDocument, AugmentedItem, AugmentationCommander }
import com.keepit.social.{ BasicAuthor, BasicUser }
import com.keepit.common.json

object WebsiteSearchController {
  private[WebsiteSearchController] val maxKeepsShown = 0
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

      val (futurePersonalKeeps, futurePersonalKeepRecipients, futureLibrariesWithWriteAccess) = {
        if (userId == SearchControllerUtil.nonUser) {
          (
            Future.successful(Map.empty[Id[NormalizedURI], Set[PersonalKeep]].withDefaultValue(Set.empty)),
            Future.successful(Map.empty[Id[NormalizedURI], Set[CrossServiceKeepRecipients]].withDefaultValue(Set.empty)),
            Future.successful(Set.empty[Id[Library]])
          )
        } else {
          (
            shoeboxClient.getPersonalKeeps(userId, uriIds.toSet),
            shoeboxClient.getPersonalKeepRecipientsOnUris(userId, uriIds.toSet),
            shoeboxClient.getLibrariesWithWriteAccess(userId)
          )
        }
      }

      getAugmentedItems(augmentationCommander)(userId, uriSearchResult).flatMap { augmentedItems =>
        val librarySearcher = libraryIndexer.getSearcher
        val (futureAugmentationFields, futureBasicUsersAndLibraries) = writesAugmentationFields(librarySearcher, futurePersonalKeepRecipients, futurePersonalKeeps, futureLibrariesWithWriteAccess, userId, maxKeepsShown, maxKeepersShown, maxLibrariesShown, maxTagsShown, augmentedItems)

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
                "uriId" -> NormalizedURI.publicId(uriId),
                "siteName" -> siteName,
                "image" -> imageOpt.map { image =>
                  Json.obj(
                    "url" -> image.path.getUrl,
                    "width" -> image.size.width,
                    "height" -> image.size.height
                  )
                },
                "score" -> hit.finalScore,
                "summary" -> json.aggressiveMinify(Json.obj( // todo(Léo): remove deprecated summary field
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
    futurePersonalKeepRecipients: Future[Map[Id[NormalizedURI], Set[CrossServiceKeepRecipients]]],
    futurePersonalKeeps: Future[Map[Id[NormalizedURI], Set[PersonalKeep]]],
    futureLibrariesWithWriteAccess: Future[Set[Id[Library]]],
    userId: Id[User],
    maxKeepsShown: Int,
    maxKeepersShown: Int,
    maxLibrariesShown: Int,
    maxTagsShown: Int,
    augmentedItems: Seq[AugmentedItem]): (Future[Seq[JsObject]], Future[(Seq[BasicUser], Seq[BasicLibrary])]) = {

    val allKeepIds = augmentedItems.flatMap(_.keeps.map(_.id)).toSet
    val futureKeepSources = shoeboxClient.getSourceAttributionForKeeps(allKeepIds)

    val limitedAugmentationInfos = augmentedItems.map(_.toLimitedAugmentationInfo(maxKeepsShown, maxKeepersShown, maxLibrariesShown, maxTagsShown))
    val allKeepsShown = limitedAugmentationInfos.map(_.keep).flatten
    val allKeepersShown = limitedAugmentationInfos.map(_.keepers).flatten
    val allLibrariesShown = limitedAugmentationInfos.map(_.libraries).flatten

    val userIds = ((allKeepsShown.flatMap(_.owner) ++ allKeepersShown.map(_._1) ++ allLibrariesShown.map(_._2)).toSet - userId).toSeq
    val userIndexById = userIds.zipWithIndex.toMap + (userId -> -1)

    val libraryRecordsAndVisibilityById = getLibraryRecordsAndVisibilityAndKind(librarySearcher, (allKeepsShown.flatMap(_.libraries) ++ allLibrariesShown.map(_._1)).toSet)

    val libraryIds = libraryRecordsAndVisibilityById.keys.toSeq // libraries that are missing from the index are implicitly dropped here (race condition)
    val libraryIndexById = libraryIds.zipWithIndex.toMap

    val futureUsers = {
      val libraryOwnerIds = libraryRecordsAndVisibilityById.values.map(_._1.ownerId)
      shoeboxClient.getBasicUsers(userIds ++ libraryOwnerIds)
    }

    val futureBasicUsersAndLibraries = {
      val futureOrganizations = shoeboxClient.getBasicOrganizationsByIds(libraryRecordsAndVisibilityById.values.flatMap(_._1.orgId).toSet)
      for {
        usersById <- futureUsers
        organizationsById <- futureOrganizations
      } yield {
        val users = userIds.map(usersById(_))
        val libraries = libraryIds.map { libId =>
          val (library, visibility, _) = libraryRecordsAndVisibilityById(libId)
          val owner = usersById(library.ownerId)
          val organization = library.orgId.map(organizationsById(_))
          makeBasicLibrary(library, visibility, owner, organization)
        }
        (users, libraries)
      }
    }

    val futureAugmentationFields = for {
      personalKeepsByUriId <- futurePersonalKeeps
      personalKeepRecipientsByUriId <- futurePersonalKeepRecipients
      librariesWithWriteAccess <- futureLibrariesWithWriteAccess
      sourceAttributionByKeepId <- futureKeepSources
      usersById <- futureUsers
    } yield {
      val allPersonalKeeps = augmentedItems.map(item => personalKeepsByUriId.getOrElse(item.uri, Set.empty))
      val allPersonalKeepRecipients = augmentedItems.map(item => personalKeepRecipientsByUriId.getOrElse(item.uri, Set.empty))
      (augmentedItems, limitedAugmentationInfos, (allPersonalKeeps zip allPersonalKeepRecipients)).zipped.map {
        case (augmentedItem, limitedInfo, (personalKeeps, personalKeepRecipients)) =>

          def shouldHideKeeper(keeperId: Id[User]): Boolean = { // ensuring consistency of keepers shown with the user's latest database data (race condition)
            !userIndexById.contains(keeperId) || (keeperId == userId && !personalKeepRecipients.exists(_.owner.contains(userId)))
          }

          def shouldHideLibrary(libraryId: Id[Library]): Boolean = { // ensuring consistency of libraries shown with the user's latest database data (race condition)
            !libraryIndexById.contains(libraryId) || (librariesWithWriteAccess.contains(libraryId) && !personalKeepRecipients.exists(_.recipients.libraries.contains(libraryId)))
          }

          def shouldHideLibraryOrKeeper(libraryId: Id[Library], keeperId: Id[User]): Boolean = shouldHideLibrary(libraryId) || shouldHideKeeper(keeperId)

          def shouldHideKeep(keep: KeepDocument): Boolean = { // ensuring consistency of keeps shown with the user's latest database data (race condition)
            (keep.users.contains(userId) || keep.libraries.exists(librariesWithWriteAccess.contains)) && !personalKeepRecipients.exists(_.id == keep.id)
          }

          val keepersIndices = limitedInfo.keepers.collect { case (keeperId, _) if !shouldHideKeeper(keeperId) => userIndexById(keeperId) }
          val librariesIndices = limitedInfo.libraries.collect {
            case (libraryId, keeperId, _) if !shouldHideLibraryOrKeeper(libraryId, keeperId) => Seq(libraryIndexById(libraryId), userIndexById(keeperId))
          }.flatten

          val (libraryId, keeperId, keptAt, note) = limitedInfo.keep match {
            case Some(keep) if !shouldHideKeep(keep) && keep.owner.exists(!shouldHideKeeper(_)) =>
              (keep.libraries.find(!shouldHideLibrary(_)), keep.owner, Some(keep.keptAt), keep.note) // canonical keep
            case _ => limitedInfo.libraries.collectFirst {
              case (libraryId, keeperId, keptAt) if !shouldHideLibraryOrKeeper(libraryId, keeperId) =>
                (Some(libraryId), Some(keeperId), Some(keptAt), None) // first accessible keep
            } orElse {
              limitedInfo.keepers.collectFirst { case (keeper, keptAt) if !shouldHideKeeper(keeper) => (None, Some(keeper), Some(keptAt), None) } // first discoverable keep
            } getOrElse (None, None, None, None)
          }

          val sources = getSources(augmentedItem, sourceAttributionByKeepId)
          val source = limitedInfo.keep.flatMap(keep => sourceAttributionByKeepId.get(keep.id))

          val libraryIndex = libraryId.map(libraryIndexById(_))
          val keeperIndex = keeperId.map(userIndexById(_))
          val author = keeperId.flatMap(id => usersById.get(id).map(BasicAuthor.fromUser(_))) orElse source.map(BasicAuthor.fromSource(_))

          Json.obj(
            "author" -> author,
            "user" -> keeperIndex,
            "library" -> libraryIndex,
            "createdAt" -> keptAt, // field named createdAt for legacy reason
            "note" -> note,
            "source" -> source.map(SourceAttribution.externalWrites.writes),
            "sources" -> sources.map(SourceAttribution.externalWrites.writes),
            "keeps" -> personalKeeps,
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
    debug: Option[String]) = MaybeUserAction.async { request =>

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
      val librarySearchContextFuture = searchFilterFuture.map { filter => SearchContext(libraryContext, parsedOrderBy, filter, disablePrefixSearch, disableFullTextSearch) }
      librarySearchCommander.searchLibraries(userId, acceptLangs, experiments, query, librarySearchContextFuture, maxLibraries, None, debugOpt, None).flatMap { librarySearchResult =>
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

        val futureOrganizations = {
          val orgIds = libraryRecordsAndVisibilityById.values.flatMap(_._1.orgId).toSet
          shoeboxClient.getBasicOrganizationsByIds(orgIds)
        }

        for {
          usersById <- futureUsers
          organizationsById <- futureOrganizations
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
                val organization = library.orgId.map(organizationsById(_))
                val details = libraryDetailsById(library.id)
                val path = LibraryPathHelper.formatLibraryPath(owner.username, organization.map(_.handle), details.slug)
                val (collaboratorIds, followerIds) = libraryMembersById(hit.id)
                val collaborators = orderWithPictureFirst(collaboratorIds.map(usersById(_)))
                val followers = orderWithPictureFirst(followerIds.map(usersById(_)))
                val description = library.description.getOrElse("")

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
                  "org" -> organization,
                  "collaborators" -> collaborators,
                  "followers" -> followers,
                  "numFollowers" -> details.numFollowers,
                  "numCollaborators" -> details.numCollaborators,
                  "numKeeps" -> details.keepCount,
                  "membership" -> details.membership,
                  "memberCount" -> (details.numFollowers + details.numCollaborators), // deprecated
                  "keepCount" -> details.keepCount, // deprecated,
                  "permissions" -> details.permissions
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

package com.keepit.search.controllers.ext

import com.google.inject.Inject
import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.controller._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.util.IdFilterCompressor
import com.keepit.search.controllers.util.{ SearchControllerUtil }
import com.keepit.model._
import com.keepit.model.UserExperimentType.ADMIN
import com.keepit.search.engine.uri.UriShardHit
import com.keepit.search.index.Searcher
import com.keepit.search.index.graph.library.LibraryIndexer
import com.keepit.search.{ SearchContext, SearchRanking, UriSearchCommander }
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
  private[ExtSearchController] val maxKeepsShown = 0
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

  def search2(
    query: String,
    maxHits: Int,
    proximityFilter: Option[String],
    lastUUIDStr: Option[String],
    context: Option[String],
    extVersion: Option[KifiExtVersion],
    debug: Option[String] = None) = UserAction { request =>

    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)

    val searchContextFuture = makeSearchFilter(getProximityScope(proximityFilter), Future.successful(None), Future.successful(None), Future.successful(None)).imap {
      filter => SearchContext(context, SearchRanking.default, filter, disablePrefixSearch = false, disableFullTextSearch = false)
    }

    val debugOpt = if (debug.isDefined && experiments.contains(ADMIN)) debug else None // debug is only for admin

    val plainResultFuture = searchCommander.searchUris(userId, acceptLangs, experiments, query, searchContextFuture, maxHits, lastUUIDStr, None, debugOpt)
    val jsonResultFuture = plainResultFuture.imap { result =>
      val textMatchesByHit = UriShardHit.getMatches(result.query, result.firstLang, result.hits)
      val experimentIdJson = result.searchExperimentId.map(id => JsNumber(id.id)).getOrElse(JsNull)
      Json.obj(
        "uuid" -> JsString(result.uuid.toString),
        "query" -> JsString(query),
        "hits" -> JsArray(result.hits.map { hit =>
          json.aggressiveMinify(Json.obj(
            "title" -> hit.titleJson,
            "url" -> hit.urlJson,
            "keepId" -> hit.externalIdJson,
            "uriId" -> NormalizedURI.publicId(Id(hit.id)),
            "matches" -> textMatchesByHit(hit)
          ))
        }),
        "myTotal" -> JsNumber(result.myTotal),
        "friendsTotal" -> JsNumber(result.friendsTotal),
        "mayHaveMore" -> JsBoolean(result.mayHaveMoreHits),
        "show" -> JsBoolean(result.show),
        "cutPoint" -> JsNumber(result.cutPoint),
        "experimentId" -> experimentIdJson,
        "context" -> JsString(IdFilterCompressor.fromSetToBase64(result.idFilter))
      )
    }

    val plainResultEnumerator = safelyFlatten(jsonResultFuture.map(r => Enumerator(r.toString))(immediate))

    val augmentationFuture = plainResultFuture.flatMap { kifiPlainResult =>
      getAugmentedItems(augmentationCommander)(userId, kifiPlainResult).flatMap { augmentedItems =>
        val librarySearcher = libraryIndexer.getSearcher
        val (futureAugmentationFields, futureBasicUsersAndLibraries) = writesAugmentationFields(librarySearcher, userId, maxKeepsShown, maxKeepersShown, maxLibrariesShown, maxTagsShown, augmentedItems)

        val futureHitsJson = futureAugmentationFields.imap(_.map(json.aggressiveMinify))
        val futureBasicUsersAndLibrariesJson = futureBasicUsersAndLibraries.imap {
          case (users, libraries) =>
            val librariesJson = libraries.map { library =>
              json.aggressiveMinify(Json.obj("id" -> library.id, "name" -> library.name, "color" -> library.color, "path" -> library.path, "secret" -> library.isSecret))
            }
            (users, librariesJson)
        }

        for {
          hitsJson <- futureHitsJson
          (users, librariesJson) <- futureBasicUsersAndLibrariesJson
        } yield Json.obj("hits" -> hitsJson, "users" -> users, "libraries" -> librariesJson)
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
    maxKeepsShown: Int,
    maxKeepersShown: Int,
    maxLibrariesShown: Int,
    maxTagsShown: Int,
    augmentedItems: Seq[AugmentedItem]): (Future[Seq[JsObject]], Future[(Seq[BasicUser], Seq[BasicLibrary])]) = {

    val allKeepIds = augmentedItems.flatMap(_.keeps.map(_.id)).toSet
    val futureKeepSources = shoeboxClient.getSourceAttributionForKeeps(allKeepIds)

    val limitedAugmentationInfos = augmentedItems.map(_.toLimitedAugmentationInfo(maxKeepsShown, maxKeepersShown, maxLibrariesShown, maxTagsShown))

    val allKeepersShown = limitedAugmentationInfos.map(_.keepers)
    val allLibrariesShown = limitedAugmentationInfos.map(_.libraries)

    val userIds = ((allKeepersShown.flatMap(_.map(_._1)) ++ allLibrariesShown.flatMap(_.map(_._2))).toSet - userId).toSeq
    val userIndexById = userIds.zipWithIndex.toMap + (userId -> -1)

    val libraryRecordsAndVisibilityById = getLibraryRecordsAndVisibilityAndKind(librarySearcher, allLibrariesShown.flatMap(_.map(_._1)).toSet)

    val libraryIds = libraryRecordsAndVisibilityById.keys.toSeq // libraries that are missing from the index are implicitly dropped here (race condition)
    val libraryIndexById = libraryIds.zipWithIndex.toMap

    val futureBasicUsersAndLibraries = {
      val libraryOwnerIds = libraryRecordsAndVisibilityById.values.map(_._1.ownerId)
      val futureUsers = shoeboxClient.getBasicUsers(userIds ++ libraryOwnerIds)
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

    val futureAugmentationFields = futureKeepSources.map { sourceAttributionByKeepId =>
      (augmentedItems zip limitedAugmentationInfos).map {
        case (augmentedItem, limitedInfo) =>

          val keepersIndices = limitedInfo.keepers.map { case (keeperId, _) => userIndexById(keeperId) }
          val librariesIndices = limitedInfo.libraries.collect {
            case (libraryId, keeperId, _) if libraryIndexById.contains(libraryId) => Seq(libraryIndexById(libraryId), userIndexById(keeperId))
          }.flatten

          val sources = getSources(augmentedItem, sourceAttributionByKeepId)
          val primaryKeepSource = limitedInfo.keep.flatMap(keep => sourceAttributionByKeepId.get(keep.id)) orElse sources.headOption

          val secret = augmentedItem.isSecret(librarySearcher)

          Json.obj(
            "keepers" -> keepersIndices,
            "keepersOmitted" -> limitedInfo.keepersOmitted,
            "keepersTotal" -> limitedInfo.keepersTotal,
            "libraries" -> librariesIndices,
            "librariesOmitted" -> limitedInfo.librariesOmitted,
            "tags" -> limitedInfo.tags,
            "tagsOmitted" -> limitedInfo.tagsOmitted,
            "secret" -> secret,
            "source" -> primaryKeepSource.map(SourceAttribution.externalWrites.writes),
            "sources" -> sources.map(SourceAttribution.externalWrites.writes)
          )
      }
    }
    (futureAugmentationFields, futureBasicUsersAndLibraries)
  }

  //external (from the extension)
  def warmUp() = UserAction { request =>
    searchCommander.warmUp(request.userId)
    Ok
  }
}

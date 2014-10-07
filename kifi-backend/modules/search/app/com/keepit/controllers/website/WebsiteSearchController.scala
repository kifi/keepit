package com.keepit.controllers.website

import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.controllers.util.SearchControllerUtil
import com.keepit.controllers.util.SearchControllerUtil.nonUser
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.iteratee.Enumerator
import com.google.inject.Inject
import com.keepit.common.controller._
import com.keepit.common.logging.Logging
import com.keepit.model.ExperimentType.ADMIN
import com.keepit.search._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import play.api.libs.json._
import com.keepit.search.graph.library.{ LibraryRecord, LibraryIndexer }
import play.api.libs.json.JsArray
import com.keepit.model._
import com.keepit.social.BasicUser
import com.keepit.search.engine.result.KifiShardHit
import com.keepit.search.util.IdFilterCompressor
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.controllers.website.WebsiteSearchController._

class WebsiteSearchController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    val shoeboxClient: ShoeboxServiceClient,
    augmentationCommander: AugmentationCommander,
    libraryIndexer: LibraryIndexer,
    searchCommander: SearchCommander,
    librarySearchCommander: LibrarySearchCommander,
    val userActionsHelper: UserActionsHelper,
    implicit val publicIdConfig: PublicIdConfiguration) extends WebsiteController(actionAuthenticator) with UserActions with SearchServiceController with SearchControllerUtil with Logging {

  def search(
    query: String,
    filter: Option[String],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
    start: Option[String] = None,
    end: Option[String] = None,
    tz: Option[String] = None,
    coll: Option[String] = None,
    debug: Option[String] = None,
    withUriSummary: Boolean = false) = JsonAction.authenticated { request =>

    val userId = request.userId
    val acceptLangs: Seq[String] = request.request.acceptLanguages.map(_.code)

    val debugOpt = if (debug.isDefined && request.experiments.contains(ADMIN)) debug else None // debug is only for admin

    val decoratedResult = searchCommander.search(userId, acceptLangs, request.experiments, query, filter, maxHits, lastUUIDStr, context, predefinedConfig = None, debugOpt, withUriSummary)

    Ok(toKifiSearchResultV1(decoratedResult)).withHeaders("Cache-Control" -> "private, max-age=10")
  }

  def search2(
    query: String,
    filter: Option[String],
    library: Option[String],
    maxHits: Int,
    lastUUIDStr: Option[String],
    context: Option[String],
    auth: Option[String],
    debug: Option[String] = None,
    withUriSummary: Boolean = false) = MaybeUserAction.async { request =>

    val libraryContextFuture = getLibraryContextFuture(library, auth, request)
    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)

    val debugOpt = if (debug.isDefined && experiments.contains(ADMIN)) debug else None // debug is only for admin

    val futureResult = searchCommander.search2(userId, acceptLangs, experiments, query, filter, libraryContextFuture, maxHits, lastUUIDStr, context, None, debugOpt).flatMap {
      case kifiPlainResult if kifiPlainResult.hits.isEmpty => Future.successful(WebsiteSearchResult(Seq.empty, kifiPlainResult.uuid, IdFilterCompressor.fromSetToBase64(kifiPlainResult.idFilter)))
      case kifiPlainResult => {
        val futureUriSummaries = {
          val uriIds = kifiPlainResult.hits.map(hit => Id[NormalizedURI](hit.id))
          shoeboxClient.getUriSummaries(uriIds)
        }

        val userIdOpt = if (userId == nonUser) None else Some(userId)
        augment(augmentationCommander, userIdOpt.get, kifiPlainResult).flatMap {
          case (infos, scores) =>
            val futureUsers = shoeboxClient.getBasicUsers(infos.flatMap(_.keeps.flatMap(_.keptBy)).distinct)
            val libraries = getLibraryNames(libraryIndexer.getSearcher, infos.flatMap(_.keeps.flatMap(_.keptIn)).distinct).map {
              case (libId, name) =>
                libId -> BasicLibrary(Library.publicId(libId), name)
            }
            for {
              users <- futureUsers
              summaries <- futureUriSummaries
            } yield {
              val websiteSearchHits = (kifiPlainResult.hits zip infos).map {
                case (hit, info) =>
                  WebsiteSearchHit.make(userIdOpt, hit, summaries(Id(hit.id)), info, scores, users, libraries)
              }
              WebsiteSearchResult(websiteSearchHits, kifiPlainResult.uuid, IdFilterCompressor.fromSetToBase64(kifiPlainResult.idFilter))
            }
        }
      }
    }
    futureResult.map(result => Ok(Json.toJson(result)))
  }

  //external (from the website)
  def warmUp() = JsonAction.authenticated { request =>
    searchCommander.warmUp(request.userId)
    Ok
  }

  def librarySearch(
    query: String,
    filter: Option[String],
    maxHits: Int,
    context: Option[String],
    debug: Option[String]) = UserAction.async { request =>

    val acceptLangs = getAcceptLangs(request)
    val (userId, experiments) = getUserAndExperiments(request)

    val debugOpt = if (debug.isDefined && experiments.contains(ADMIN)) debug else None // debug is only for admin

    librarySearchCommander.librarySearch(userId, acceptLangs, experiments, query, filter, context, maxHits, None, debugOpt).map { libraryShardResult =>
      val libraries = {
        val librarySearcher = libraryIndexer.getSearcher
        libraryShardResult.hits.map(_.id).map { libId =>
          libId -> LibraryRecord.retrieve(librarySearcher, libId)
        }.toMap
      }

      val json = JsArray(libraryShardResult.hits.map { hit =>
        val library = libraries(hit.id)
        Json.obj(
          "id" -> Library.publicId(hit.id),
          "score" -> hit.score,
          "name" -> library.map(_.name),
          "description" -> library.map(_.description.getOrElse("")),
          "mostRelevantKeep" -> hit.keep.map { case (_, keepRecord) => Json.obj("id" -> keepRecord.externalId, "title" -> JsString(keepRecord.title.getOrElse("")), "url" -> keepRecord.url) }
        )
      })
      Ok(Json.toJson(json))
    }
  }
}

object WebsiteSearchController {

  import java.text.Normalizer
  import scala.collection.mutable

  case class WebsiteSearchResult(hits: Seq[WebsiteSearchHit], uuid: ExternalId[ArticleSearchResult], context: String)

  case class BasicLibrary(id: PublicId[Library], name: String)
  case class BasicKeep(user: BasicUser, library: Option[BasicLibrary])

  case class WebsiteSearchHit(
    title: String,
    url: String,
    score: Float,
    summary: URISummary,
    tags: Seq[Hashtag],
    myKeeps: Seq[BasicLibrary],
    moreKeeps: Seq[BasicKeep],
    otherKeeps: Int)

  object WebsiteSearchHit {
    private val diacriticalMarksRegex = "\\p{InCombiningDiacriticalMarks}+".r
    @inline private def normalize(tag: Hashtag): String = diacriticalMarksRegex.replaceAllIn(Normalizer.normalize(tag.tag.trim, Normalizer.Form.NFD), "").toLowerCase

    def make(userId: Option[Id[User]], kifiShardHit: KifiShardHit, summary: URISummary, augmentationInfo: AugmentationInfo, scores: AugmentationScores, users: Map[Id[User], BasicUser], libraries: Map[Id[Library], BasicLibrary]): WebsiteSearchHit = {
      val (myRestrictedKeeps, moreRestrictedKeeps) = augmentationInfo.keeps.partition(userId.isDefined && _.keptBy == userId)

      // Keeps
      val myKeeps = myRestrictedKeeps.flatMap(_.keptIn).sortBy(scores.byLibrary).map(libraries(_))

      var uniqueKeepers = mutable.HashSet[Id[User]]()
      userId.foreach(uniqueKeepers += _)
      val moreKeeps = moreRestrictedKeeps.sortBy(keep => (keep.keptBy.map(scores.byUser), keep.keptIn.map(scores.byLibrary))).collect {
        case RestrictedKeepInfo(_, keptIn, Some(keeperId), _) if !uniqueKeepers.contains(keeperId) =>
          uniqueKeepers += keeperId
          BasicKeep(users(keeperId), keptIn.map(libraries(_)))
      }

      // Tags
      val primaryKeep = kifiShardHit.libraryId.flatMap { libId => augmentationInfo.keeps.find(_.keptIn.map(_.id == libId).getOrElse(false)) }
      val primaryTags = primaryKeep.toSeq.flatMap(_.tags.toSeq.sortBy(-scores.tagScores.getOrElse(_, 0f)))
      var uniqueNormalizedTags = mutable.HashSet() ++ primaryTags.map(normalize)
      val moreTags = augmentationInfo.keeps.map(_.tags).flatten.toSeq.sortBy(-scores.tagScores.getOrElse(_, 0f)).filter { tag =>
        val normalizedTag = normalize(tag)
        val showTag = !uniqueNormalizedTags.contains(normalizedTag)
        uniqueNormalizedTags += normalizedTag
        showTag
      }
      val tags = primaryTags ++ moreTags

      WebsiteSearchHit(
        title = kifiShardHit.title,
        url = kifiShardHit.url,
        score = kifiShardHit.finalScore,
        summary = summary,
        tags = tags,
        myKeeps = myKeeps,
        moreKeeps = moreKeeps,
        otherKeeps = augmentationInfo.otherDiscoverableKeeps + augmentationInfo.otherPublishedKeeps
      )
    }
  }

  object WebsiteSearchResult {
    implicit val writes: Writes[WebsiteSearchResult] = {
      implicit val libraryFormat = Json.writes[BasicLibrary]
      implicit val keepFormat = Json.writes[BasicKeep]
      implicit val hitFormat = Json.writes[WebsiteSearchHit]
      Json.writes[WebsiteSearchResult]
    }
  }
}

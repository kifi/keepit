package com.keepit.controllers.util

import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.controller.{ UserRequest, MaybeUserRequest }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.controllers.util.SearchControllerUtil._
import com.keepit.model._
import com.keepit.search.engine.result.KifiPlainResult
import com.keepit.search.result.{ ResultUtil, KifiSearchResult }
import com.keepit.search.util.IdFilterCompressor
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Enumerator
import play.api.mvc.RequestHeader
import com.keepit.common.core._

import scala.concurrent.Future
import com.keepit.search._
import com.keepit.common.akka.SafeFuture
import com.keepit.search.result.DecoratedResult
import play.api.libs.json.{ Json, JsObject }
import com.keepit.search.graph.library.{ LibraryRecord, LibraryIndexable }

import scala.util.{ Failure, Success }
import com.keepit.search.augmentation._
import com.keepit.social.BasicUser

object SearchControllerUtil {
  val nonUser = Id[User](-1L)
}

trait SearchControllerUtil {

  val shoeboxClient: ShoeboxServiceClient

  implicit val publicIdConfig: PublicIdConfiguration

  @inline def safelyFlatten[E](eventuallyEnum: Future[Enumerator[E]]): Enumerator[E] = Enumerator.flatten(new SafeFuture(eventuallyEnum))

  @inline
  def reactiveEnumerator(futureSeq: Seq[Future[String]]) = {
    // Returns successful results of Futures in the order they are completed, reactively
    Enumerator.interleave(futureSeq.map { future =>
      safelyFlatten(future.map(str => Enumerator(", " + str))(immediate))
    })
  }

  def uriSummaryInfoFuture(plainResultFuture: Future[KifiPlainResult]): Future[String] = {
    plainResultFuture.flatMap { r =>
      val uriIds = r.hits.map(h => Id[NormalizedURI](h.id))
      if (uriIds.nonEmpty) {
        shoeboxClient.getUriSummaries(uriIds).map { uriSummaries =>
          KifiSearchResult.uriSummaryInfoV2(uriIds.map { uriId => uriSummaries.get(uriId) }).toString
        }
      } else {
        Future.successful(KifiSearchResult.uriSummaryInfoV2(Seq()).toString)
      }
    }
  }

  def toKifiSearchResultV2(kifiPlainResult: KifiPlainResult): JsObject = {
    KifiSearchResult.v2(
      kifiPlainResult.uuid,
      kifiPlainResult.query,
      kifiPlainResult.firstLang,
      kifiPlainResult.hits,
      kifiPlainResult.myTotal,
      kifiPlainResult.friendsTotal,
      kifiPlainResult.mayHaveMoreHits,
      kifiPlainResult.show,
      kifiPlainResult.cutPoint,
      kifiPlainResult.searchExperimentId,
      IdFilterCompressor.fromSetToBase64(kifiPlainResult.idFilter)).json
  }

  def toKifiSearchResultV1(decoratedResult: DecoratedResult): JsObject = {
    KifiSearchResult.v1(
      decoratedResult.uuid,
      decoratedResult.query,
      ResultUtil.toKifiSearchHits(decoratedResult.hits),
      decoratedResult.myTotal,
      decoratedResult.friendsTotal,
      decoratedResult.othersTotal,
      decoratedResult.mayHaveMoreHits,
      decoratedResult.show,
      decoratedResult.searchExperimentId,
      IdFilterCompressor.fromSetToBase64(decoratedResult.idFilter),
      Nil).json
  }

  def augment(augmentationCommander: AugmentationCommander, librarySearcher: Searcher)(userId: Id[User], maxKeepersShown: Int, maxLibrariesShown: Int, maxTagsShown: Int, kifiPlainResult: KifiPlainResult): Future[(Seq[JsObject], Seq[Id[User]], Seq[Id[Library]])] = {
    getAugmentedItems(augmentationCommander)(userId, kifiPlainResult).map { augmentedItems =>
      writesAugmentationFields(librarySearcher, userId, maxKeepersShown, maxLibrariesShown, maxTagsShown, augmentedItems)
    }
  }

  private def getAugmentedItems(augmentationCommander: AugmentationCommander)(userId: Id[User], kifiPlainResult: KifiPlainResult): Future[Seq[AugmentedItem]] = {
    val items = kifiPlainResult.hits.map { hit => AugmentableItem(Id(hit.id), hit.libraryId.map(Id(_))) }
    val previousItems = (kifiPlainResult.idFilter.map(Id[NormalizedURI](_)) -- items.map(_.uri)).map(AugmentableItem(_, None)).toSet
    val context = AugmentationContext.uniform(userId, previousItems ++ items)
    val augmentationRequest = ItemAugmentationRequest(items.toSet, context)
    augmentationCommander.getAugmentedItems(augmentationRequest).imap { augmentedItems => items.map(augmentedItems(_)) }
  }

  private def writesAugmentationFields(
    librarySearcher: Searcher,
    userId: Id[User],
    maxKeepersShown: Int,
    maxLibrariesShown: Int,
    maxTagsShown: Int,
    augmentedItems: Seq[AugmentedItem]): (Seq[JsObject], Seq[Id[User]], Seq[Id[Library]]) = {

    val limitedAugmentationInfos = augmentedItems.map(_.toLimitedAugmentationInfo(librarySearcher, maxKeepersShown, maxLibrariesShown, maxTagsShown))
    val allKeepersShown = limitedAugmentationInfos.map(_.keepers)
    val allLibrariesShown = limitedAugmentationInfos.map(_.libraries)

    val userIds = ((allKeepersShown.flatten ++ allLibrariesShown.flatMap(_.map(_._2))).toSet - userId).toSeq
    val userIndexById = userIds.zipWithIndex.toMap + (userId -> -1)

    val libraryIds = allLibrariesShown.flatMap(_.map(_._1)).distinct
    val libraryIndexById = libraryIds.zipWithIndex.toMap

    val augmentationFields = limitedAugmentationInfos.map { limitedInfo =>

      val keepersIndices = limitedInfo.keepers.map(userIndexById(_))
      val librariesIndices = limitedInfo.libraries.flatMap { case (libraryId, keeperId) => Seq(libraryIndexById(libraryId), userIndexById(keeperId)) }

      Json.obj(
        "secret" -> limitedInfo.secret,
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
    (augmentationFields, userIds, libraryIds)
  }

  def getLibraryRecordsWithSecrecy(librarySearcher: Searcher, libraryIds: Set[Id[Library]]): Map[Id[Library], (LibraryRecord, Boolean)] = {
    libraryIds.map { libId =>
      libId -> (LibraryIndexable.getRecord(librarySearcher, libId).get, LibraryIndexable.isSecret(librarySearcher, libId))
    }.toMap
  }

  def makeBasicLibrary(library: LibraryRecord, owner: BasicUser, secret: Boolean)(implicit publicIdConfig: PublicIdConfiguration): BasicLibrary = {
    val path = Library.formatLibraryPath(owner.username, owner.externalId, library.slug)
    BasicLibrary(Library.publicId(library.id), library.name, path, secret)
  }

  def getUserAndExperiments(request: MaybeUserRequest[_]): (Id[User], Set[ExperimentType]) = {
    request match {
      case userRequest: UserRequest[_] => (userRequest.userId, userRequest.experiments)
      case _ => (nonUser, Set.empty[ExperimentType])
    }
  }

  def getAcceptLangs(requestHeader: RequestHeader): Seq[String] = requestHeader.acceptLanguages.map(_.code)

  def getLibraryContextFuture(library: Option[String], auth: Option[String], requestHeader: RequestHeader)(implicit publicIdConfig: PublicIdConfiguration): Future[LibraryContext] = {
    library match {
      case Some(libPublicId) =>
        Library.decodePublicId(PublicId[Library](libPublicId)) match {
          case Success(libId) =>
            val libraryAccess = requestHeader.session.get("library_access").map { _.split("/") }
            (auth, libraryAccess) match {
              case (Some(_), Some(Array(libPublicIdInCookie, hashedPassPhrase))) if (libPublicIdInCookie == libPublicId) =>
                shoeboxClient.canViewLibrary(libId, None, auth, Some(HashedPassPhrase(hashedPassPhrase))).map { authorized =>
                  if (authorized) LibraryContext.Authorized(libId.id) else LibraryContext.NotAuthorized(libId.id)
                }
              case _ =>
                Future.successful(LibraryContext.NotAuthorized(libId.id))
            }
          case Failure(e) =>
            log.warn(s"invalid library public id: $libPublicId", e)
            Future.successful(LibraryContext.Invalid)
        }
      case _ =>
        Future.successful(LibraryContext.None)
    }
  }
}

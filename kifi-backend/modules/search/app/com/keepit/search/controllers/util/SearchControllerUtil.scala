package com.keepit.search.controllers.util

import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.controller.{ UserRequest, MaybeUserRequest }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.search.controllers.util.SearchControllerUtil._
import com.keepit.model._
import com.keepit.search.engine.uri.UriSearchResult
import com.keepit.search.index.Searcher
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
import play.api.libs.json.JsObject
import com.keepit.search.index.graph.library.{ LibraryRecord, LibraryIndexable }

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

  def toKifiSearchResultV2(kifiPlainResult: UriSearchResult): JsObject = {
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

  def toKifiSearchResultV1(decoratedResult: DecoratedResult, sanitize: Boolean = false): JsObject = {
    KifiSearchResult.v1(
      decoratedResult.uuid,
      decoratedResult.query,
      ResultUtil.toKifiSearchHits(decoratedResult.hits, sanitize),
      decoratedResult.myTotal,
      decoratedResult.friendsTotal,
      decoratedResult.othersTotal,
      decoratedResult.mayHaveMoreHits,
      decoratedResult.show,
      decoratedResult.searchExperimentId,
      IdFilterCompressor.fromSetToBase64(decoratedResult.idFilter),
      Nil).json
  }

  def getAugmentedItems(augmentationCommander: AugmentationCommander)(userId: Id[User], kifiPlainResult: UriSearchResult): Future[Seq[AugmentedItem]] = {
    val items = kifiPlainResult.hits.map { hit => AugmentableItem(Id(hit.id), hit.libraryId.map(Id(_))) }
    val previousItems = (kifiPlainResult.idFilter.map(Id[NormalizedURI](_)) -- items.map(_.uri)).map(AugmentableItem(_, None)).toSet
    val context = AugmentationContext.uniform(userId, previousItems ++ items)
    val augmentationRequest = ItemAugmentationRequest(items.toSet, context)
    augmentationCommander.getAugmentedItems(augmentationRequest).imap { augmentedItems => items.map(augmentedItems(_)) }
  }

  def getLibraryRecordsAndVisibility(librarySearcher: Searcher, libraryIds: Set[Id[Library]]): Map[Id[Library], (LibraryRecord, LibraryVisibility)] = {
    for {
      libId <- libraryIds
      record <- LibraryIndexable.getRecord(librarySearcher, libId)
      visibility <- LibraryIndexable.getVisibility(librarySearcher, libId.id)
    } yield {
      libId -> (record, visibility)
    }
  }.toMap

  protected def makeBasicLibrary(library: LibraryRecord, visibility: LibraryVisibility, owner: BasicUser)(implicit publicIdConfig: PublicIdConfiguration): BasicLibrary = {
    val path = Library.formatLibraryPath(owner.username, library.slug)
    BasicLibrary(Library.publicId(library.id), library.name, path, visibility, library.color)
  }

  def getUserAndExperiments(request: MaybeUserRequest[_]): (Id[User], Set[ExperimentType]) = {
    request match {
      case userRequest: UserRequest[_] => (userRequest.userId, userRequest.experiments)
      case _ => (nonUser, Set.empty[ExperimentType])
    }
  }

  def getAcceptLangs(requestHeader: RequestHeader): Seq[String] = requestHeader.acceptLanguages.map(_.code)

  def getLibraryFilterFuture(library: Option[PublicId[Library]], auth: Option[String], requestHeader: RequestHeader)(implicit publicIdConfig: PublicIdConfiguration): Future[LibraryContext] = {
    library match {
      case Some(libPublicId) =>
        Library.decodePublicId(libPublicId) match {
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

  def getUserFilterFuture(filter: Option[String]): Future[Option[Either[Id[User], String]]] = {
    filter.flatMap(ExternalId.asOpt[User]) match {
      case Some(userId) => shoeboxClient.getUserIdsByExternalIds(Seq(userId)).imap(_.headOption.map(Left(_)))
      case _ => Future.successful(filter.map(Right(_)))
    }
  }
}

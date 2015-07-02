package com.keepit.search.controllers.util

import com.keepit.common.concurrent.ExecutionContext._
import com.keepit.common.controller.{ UserRequest, MaybeUserRequest }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.search.controllers.util.SearchControllerUtil._
import com.keepit.model._
import com.keepit.search.engine.uri.UriSearchResult
import com.keepit.search.index.Searcher
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Enumerator
import play.api.mvc.RequestHeader
import com.keepit.common.core._

import scala.concurrent.Future
import com.keepit.search._
import com.keepit.common.akka.SafeFuture
import com.keepit.search.index.graph.library.{ LibraryRecord, LibraryIndexable }

import scala.util.{ Try, Failure, Success }
import com.keepit.search.augmentation._
import com.keepit.social.BasicUser

object SearchControllerUtil {
  val nonUser = Id[User](-1L)
}

trait SearchControllerUtil {

  val shoeboxClient: ShoeboxServiceClient

  implicit val publicIdConfig: PublicIdConfiguration

  @inline def safelyFlatten[E](eventuallyEnum: Future[Enumerator[E]]): Enumerator[E] = Enumerator.flatten(new SafeFuture(eventuallyEnum))

  def getAugmentedItems(augmentationCommander: AugmentationCommander)(userId: Id[User], kifiPlainResult: UriSearchResult): Future[Seq[AugmentedItem]] = {
    val items = kifiPlainResult.hits.map { hit =>
      // todo(Léo): this is a hack to make sure keeps from a library are always shown canonically when searching that library.
      // todo(Léo): Would need a similar hack to search user profiles.
      // todo(Léo): Ideally we filter the search space within ScoreVectorSources and the final results (e.g. porn) within ResultCollectors (possibly with a special record flag).
      val libraryId = (Try(kifiPlainResult.searchFilter.libraryContext.get).toOption orElse hit.libraryId).map(Id[Library](_))
      AugmentableItem(Id(hit.id), libraryId)
    }
    val previousItems = (kifiPlainResult.idFilter.map(Id[NormalizedURI](_)) -- items.map(_.uri)).map(AugmentableItem(_, None)).toSet
    val context = AugmentationContext.uniform(userId, previousItems ++ items)
    val augmentationRequest = ItemAugmentationRequest(items.toSet, context)
    augmentationCommander.getAugmentedItems(augmentationRequest).imap { augmentedItems => items.map(augmentedItems(_)) }
  }

  def getLibraryRecordsAndVisibilityAndKind(librarySearcher: Searcher, libraryIds: Set[Id[Library]]): Map[Id[Library], (LibraryRecord, LibraryVisibility, LibraryKind)] = {
    for {
      libId <- libraryIds
      record <- LibraryIndexable.getRecord(librarySearcher, libId)
      visibility <- LibraryIndexable.getVisibility(librarySearcher, libId.id)
      kind <- LibraryIndexable.getKind(librarySearcher, libId.id)
    } yield {
      libId -> (record, visibility, kind)
    }
  }.toMap

  protected def makeBasicLibrary(library: LibraryRecord, visibility: LibraryVisibility, owner: BasicUser, org: Option[Organization])(implicit publicIdConfig: PublicIdConfiguration): BasicLibrary = {
    val path = LibraryPathHelper.formatLibraryPath(owner, org, library.slug)
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
                shoeboxClient.canViewLibrary(libId, None, auth).map { authorized =>
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

package com.keepit.search.controllers.util

import com.keepit.common.controller.{ UserRequest, MaybeUserRequest }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.search.controllers.util.SearchControllerUtil._
import com.keepit.model._
import com.keepit.search.engine.uri.UriSearchResult
import com.keepit.search.index.Searcher
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.iteratee.Enumerator
import play.api.mvc.RequestHeader
import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }
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

  @inline def safelyFlatten[E](eventuallyEnum: Future[Enumerator[E]])(implicit ec: ExecutionContext): Enumerator[E] = Enumerator.flatten(new SafeFuture(eventuallyEnum))

  def getAugmentedItems(augmentationCommander: AugmentationCommander)(userId: Id[User], kifiPlainResult: UriSearchResult): Future[Seq[AugmentedItem]] = {
    val items = kifiPlainResult.hits.map { hit =>
      // todo(Léo): this is a hack to make sure keeps from a library are always shown canonically when searching that library.
      // todo(Léo): Would need a similar hack to search user profiles.
      // todo(Léo): Ideally we filter the search space within ScoreVectorSources and the final results (e.g. porn) within ResultCollectors (possibly with a special record flag).
      val libraryId = kifiPlainResult.searchFilter.library.map(_.id) orElse hit.libraryId.map(Id[Library](_))
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

  def makeSearchFilter(proximity: Option[ProximityScope], libraryScope: Future[Option[LibraryScope]], userScope: Future[Option[UserScope]], organizationScope: Future[Option[OrganizationScope]], context: Option[String])(implicit ec: ExecutionContext): Future[SearchFilter] = {
    for {
      library <- libraryScope
      user <- userScope
      organization <- organizationScope
    } yield SearchFilter(proximity, user, library, organization, context)
  }

  def getProximityScope(proximityStr: Option[String]): Option[ProximityScope] = proximityStr.flatMap(ProximityScope.parse)

  def getLibraryScope(libraryIdStr: Option[String], userId: Option[Id[User]], auth: Option[String])(implicit publicIdConfig: PublicIdConfiguration): Future[Option[LibraryScope]] = {
    libraryIdStr.map(getLibraryScope(_, userId, auth).imap(Some(_))) getOrElse Future.successful(None)
  }
  private def getLibraryScope(libraryIdStr: String, userId: Option[Id[User]], auth: Option[String])(implicit publicIdConfig: PublicIdConfiguration): Future[LibraryScope] = {
    Library.decodePublicId(PublicId[Library](libraryIdStr)) match {
      case Success(libId) => shoeboxClient.canViewLibrary(libId, userId, auth).imap(LibraryScope(libId, _))
      case Failure(e) => Future.failed(e)
    }
  }

  def getUserScope(userIdStr: Option[String])(implicit publicIdConfig: PublicIdConfiguration): Future[Option[UserScope]] = {
    userIdStr.map(getUserScope(_).imap(Some(_))) getOrElse Future.successful(None)
  }
  private def getUserScope(userIdStr: String): Future[UserScope] = {
    Try(ExternalId[User](userIdStr)) match {
      case Success(extUserId) => shoeboxClient.getUserIdsByExternalIds(Seq(extUserId)).imap { case Seq(userId) => UserScope(userId) }
      case Failure(e) => Future.failed(e)
    }
  }

  def getOrganizationScope(organizationIdStr: Option[String], userId: Option[Id[User]])(implicit publicIdConfig: PublicIdConfiguration): Future[Option[OrganizationScope]] = {
    organizationIdStr.map(getOrganizationScope(_, userId).imap(Some(_))) getOrElse Future.successful(None)
  }
  private def getOrganizationScope(organizationIdStr: String, userId: Option[Id[User]])(implicit publicIdConfig: PublicIdConfiguration): Future[OrganizationScope] = {
    Organization.decodePublicId(PublicId[Organization](organizationIdStr)) match {
      case Success(libId) => {
        val authorized = userId.map(shoeboxClient.hasOrganizationMembership(libId, _)) getOrElse Future.successful(false)
        authorized.imap(OrganizationScope(libId, _))
      }
      case Failure(e) => Future.failed(e)
    }
  }
}

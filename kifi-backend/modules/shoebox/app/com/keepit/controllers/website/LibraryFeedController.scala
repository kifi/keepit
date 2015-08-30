package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.db.slick.Database
import com.keepit.common.seo.{ AtomCommander, FeedCommander }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import play.api.db
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.{ ResponseHeader, Result }

import scala.concurrent.Future

class LibraryFeedController @Inject() (
    db: Database,
    userCommander: UserCommander,
    libraryInfoCommander: LibraryInfoCommander,
    libraryAccessCommander: LibraryAccessCommander,
    libPathCommander: PathCommander,
    experimentCommander: LocalUserExperimentCommander,
    feedCommander: FeedCommander,
    fortyTwoConfig: FortyTwoConfig,
    atomCommander: AtomCommander,
    handleCommander: HandleCommander,
    val userActionsHelper: UserActionsHelper) extends UserActions with ShoeboxServiceController {

  private def lookupByHandle(handle: Handle): Option[(Either[Organization, User], Option[Int])] = {
    val handleOwnerOpt = db.readOnlyMaster { implicit session => handleCommander.getByHandle(handle) }
    handleOwnerOpt.map {
      case (handleOwner, isPrimary) =>
        val foundHandle = handleOwner match {
          case Left(org) => Handle.fromOrganizationHandle(org.handle)
          case Right(user) => Handle.fromUsername(user.username)
        }

        if (foundHandle != handle) {
          // owner moved or handle normalization
          (handleOwner, Some(if (!isPrimary) MOVED_PERMANENTLY else SEE_OTHER))
        } else {
          (handleOwner, None)
        }
    }
  }

  private def dropPathSegment(uri: String): String = uri.drop(1).dropWhile(!"/?".contains(_))

  def libraryRSSFeed(handle: Handle, librarySlug: String, authToken: Option[String] = None, count: Int = 20, offset: Int = 0) = MaybeUserAction.async { implicit request =>
    lookupByHandle(handle) flatMap {
      case (handleOwner, redirectStatusOpt) =>
        val handleSpace: LibrarySpace = handleOwner match {
          case Left(org) => org.id.get
          case Right(user) => user.id.get
        }
        libraryInfoCommander.getLibraryBySlugOrAlias(handleSpace, LibrarySlug(librarySlug)) map {
          case (library, isLibraryAlias) =>
            if (library.slug.value != librarySlug || redirectStatusOpt.isDefined) { // library moved
              val uri = libPathCommander.getPathForLibraryUrlEncoded(library) + dropPathSegment(dropPathSegment(request.uri))
              val status = if (!isLibraryAlias || redirectStatusOpt.contains(303)) 303 else 301
              Future.successful(Redirect(uri, status))
            } else if (libraryAccessCommander.canViewLibrary(request.userOpt.flatMap(_.id), library, authToken)) {
              feedCommander.libraryFeed(library, count, offset) map { rss =>
                Result(
                  header = ResponseHeader(200, Map(CONTENT_TYPE -> "application/rss+xml; charset=utf-8")),
                  body = feedCommander.wrap(rss)
                )
              }
            } else {
              Future.successful(NotFound(views.html.error.notFound(request.path)))
            }
        }
    } getOrElse Future.successful(NotFound(views.html.error.notFound(request.path)))
  }

  def libraryAtomFeed(handle: Handle, librarySlug: String, authToken: Option[String] = None, count: Int = 20, offset: Int = 0) = MaybeUserAction.async { implicit request =>
    lookupByHandle(handle) flatMap {
      case (handleOwner, userRedirectStatusOpt) =>
        val handleSpace: LibrarySpace = handleOwner match {
          case Left(org) => org.id.get
          case Right(user) => user.id.get
        }
        libraryInfoCommander.getLibraryBySlugOrAlias(handleSpace, LibrarySlug(librarySlug)) map {
          case (library, isLibraryAlias) =>
            if (library.slug.value != librarySlug || userRedirectStatusOpt.isDefined) { // library moved
              val uri = libPathCommander.getPathForLibraryUrlEncoded(library) + dropPathSegment(dropPathSegment(request.uri))
              val status = if (!isLibraryAlias || userRedirectStatusOpt.contains(303)) 303 else 301
              Future.successful(Redirect(uri, status))
            } else if (libraryAccessCommander.canViewLibrary(request.userOpt.flatMap(_.id), library, authToken)) {
              atomCommander.libraryFeed(library, count, offset) map { atom =>
                Result(
                  header = ResponseHeader(200, Map(CONTENT_TYPE -> "application/atom+xml; charset=utf-8")),
                  body = feedCommander.wrap(atom)
                )
              }
            } else {
              Future.successful(NotFound(views.html.error.notFound(request.path)))
            }
        }
    } getOrElse Future.successful(NotFound(views.html.error.notFound(request.path)))
  }
}

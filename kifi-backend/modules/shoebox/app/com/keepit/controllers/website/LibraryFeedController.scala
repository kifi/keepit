package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.{ LibraryCommander, LocalUserExperimentCommander, UserCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.seo.{ AtomCommander, FeedCommander }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.{ ResponseHeader, Result }

import scala.concurrent.Future

/**
 * Created by colinlane on 5/22/15.
 */
class LibraryFeedController @Inject() (val userCommander: UserCommander,
    val libraryCommander: LibraryCommander,
    val experimentCommander: LocalUserExperimentCommander,
    val feedCommander: FeedCommander,
    val fortyTwoConfig: FortyTwoConfig,
    val atomCommander: AtomCommander,
    val userActionsHelper: UserActionsHelper) extends UserActions with ShoeboxServiceController {

  private def lookupUsername(username: Username): Option[(User, Option[Int])] = {
    userCommander.getUserByUsernameOrAlias(username) map {
      case (user, isAlias) =>
        if (user.username != username) { // user moved or username normalization
          (user, Some(if (isAlias) 301 else 303))
        } else {
          (user, None)
        }
    }
  }

  private def dropPathSegment(uri: String): String = uri.drop(1).dropWhile(!"/?".contains(_))

  def libraryRSSFeed(username: Username, librarySlug: String, authToken: Option[String] = None, count: Int = 20, offset: Int = 0) = MaybeUserAction.async { implicit request =>
    lookupUsername(username) flatMap {
      case (user, userRedirectStatusOpt) =>
        libraryCommander.getLibraryBySlugOrAlias(user.id.get, LibrarySlug(librarySlug)) map {
          case (library, isLibraryAlias) =>
            if (library.slug.value != librarySlug || userRedirectStatusOpt.isDefined) { // library moved
              val uri = Library.formatLibraryPathUrlEncoded(user.username, library.slug) + dropPathSegment(dropPathSegment(request.uri))
              val status = if (!isLibraryAlias || userRedirectStatusOpt.contains(303)) 303 else 301
              Future.successful(Redirect(uri, status))
            } else if (libraryCommander.canViewLibrary(request.userOpt.flatMap(_.id), library, authToken)) {
              feedCommander.libraryFeed(library, count, offset) map { rss =>
                Result(
                  header = ResponseHeader(200, Map(CONTENT_TYPE -> "application/rss+xml")),
                  body = feedCommander.wrap(rss)
                )
              }
            } else {
              Future.successful(NotFound(views.html.error.notFound(request.path)))
            }
        }
    } getOrElse Future.successful(NotFound(views.html.error.notFound(request.path)))
  }

  def libraryAtomFeed(username: Username, librarySlug: String, authToken: Option[String] = None, count: Int = 20, offset: Int = 0) = MaybeUserAction.async { implicit request =>
    lookupUsername(username) flatMap {
      case (user, userRedirectStatusOpt) =>
        libraryCommander.getLibraryBySlugOrAlias(user.id.get, LibrarySlug(librarySlug)) map {
          case (library, isLibraryAlias) =>
            if (library.slug.value != librarySlug || userRedirectStatusOpt.isDefined) { // library moved
              val uri = Library.formatLibraryPathUrlEncoded(user.username, library.slug) + dropPathSegment(dropPathSegment(request.uri))
              val status = if (!isLibraryAlias || userRedirectStatusOpt.contains(303)) 303 else 301
              Future.successful(Redirect(uri, status))
            } else if (libraryCommander.canViewLibrary(request.userOpt.flatMap(_.id), library, authToken)) {
              atomCommander.libraryFeed(library, count, offset) map { atom =>
                Result(
                  header = ResponseHeader(200, Map(CONTENT_TYPE -> "application/atom+xml")),
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

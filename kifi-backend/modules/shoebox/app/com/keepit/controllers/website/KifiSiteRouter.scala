package com.keepit.controllers.website

import com.google.inject.{Inject, Singleton}
import com.keepit.common.controller.{ActionAuthenticator, ShoeboxServiceController, WebsiteController}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.model.{LibraryRepo, LibrarySlug, UserRepo, Username}
import play.api.mvc.Request

import scala.concurrent.Future

private sealed trait Routeable
private case class Angular(preload: Seq[Request => Future[String]] = Seq.empty) extends Routeable
private case class AboutAssets(path: Path) extends Routeable
private case object Error404 extends Routeable

case class Path(requestPath: String) {
  val path = if (requestPath.indexOf('/') == 0) {
    requestPath.drop(1)
  } else {
    requestPath
  }
  val split = path.split("/")
  val primary = split.head
  val secondary = split.tail.headOption
}


@Singleton // holds state for performance reasons
class KifiSiteRouter @Inject() (
  actionAuthenticator: ActionAuthenticator,
  angularRouter: AngularRouter)
    extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def app() = HtmlAction.apply(authenticatedAction = { request =>
    AngularDistAssets.angularApp()
    Ok
  }, unauthenticatedAction = { request =>
    Ok
  })

  def route(request: Request): Routeable = {
    val path = Path(request.path)

    angularRouter.route(request, path) getOrElse Error404 // todo(andrew): add about assets
  }

}

@Singleton
class AngularRouter @Inject() (db: Database, userRepo: UserRepo, libraryRepo: LibraryRepo) {

  def route(request: Request, path: Path): Option[Routeable] = {
    ngStaticPage(path) orElse userOrLibrary(path)
  }

  def injectUser(request: Request) = Future {
    "hey"
  }
  private val ngFixedRoutes: Map[String, Seq[Request => Future[String]]] = Map(
    "invite" -> Seq(injectUser _),
    "profile" -> Seq(injectUser _),
    "kifeeeed" -> Seq(injectUser _),
    "find" -> Seq(injectUser _)
  )
  private val ngPrefixRoutes: Map[String, Seq[Request => Future[String]]] = Map(
    "friends" -> Seq(),
    "keep" -> Seq(),
    "tag" -> Seq(),
    "helprank" -> Seq()
  )

  private val dataOnEveryAngularPage = Seq(injectUser _)

  // combined to re-use User lookup
  private def userOrLibrary(path: Path)(implicit session: RSession): Option[Angular] = {
    if (path.split.length == 1 || path.split.length == 2) {
      val userOpt = userRepo.getUsername(Username(path.primary))
      if (userOpt.isDefined) {
        if (path.split.length == 1) { // user profile page
          Some(Angular()) // great place to preload request data since we have `user` available
        } else {
          val libOpt = libraryRepo.getBySlugAndUserId(userOpt.get.id.get, LibrarySlug(path.secondary.get))
          if (libOpt.isDefined) {
            // todo: Determine if user has access. Else, 404 it (github style)
            Some(Angular()) // great place to preload request data since we have `lib` available
          } else {
            None
          }
        }
      } else {
        None
      }
    } else {
      None
    }
  }

  // Some means to serve Angular. The Seq is possible injected data to include
  private def ngStaticPage(path: Path) = {
    (ngFixedRoutes.get(path.path) orElse ngPrefixRoutes.get(path.primary)).map { dataLoader =>
      Angular(dataLoader)
    }
  }
}

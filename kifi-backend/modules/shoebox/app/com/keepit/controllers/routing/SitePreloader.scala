package com.keepit.controllers.routing

import com.google.inject.Inject
import com.keepit.commanders.KeepCommander
import com.keepit.common.controller.{ MaybeUserRequest, NonUserRequest, UserRequest }
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.controllers.website.{ KeepsController, UserController }
import com.keepit.model.User
import play.api.libs.json.{ JsObject, Json }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

class SitePreloader @Inject() (
    userController: UserController,
    keepCommander: KeepCommander,
    implicit val ec: ExecutionContext) extends Logging {

  def preload(requests: Seq[PreloadRequest])(implicit request: MaybeUserRequest[_]): Seq[Future[String]] = {
    requests.map { r =>
      requestToData(r)(request).map(m => m.map(buildPayload(r.path, _)).getOrElse(""))
    }
  }

  private def requestToData[T](preload: PreloadRequest)(request: MaybeUserRequest[_]): Future[Option[JsObject]] = noExplode {
    import com.keepit.controllers.routing.PreloadRequest._
    val none = Future.successful(None)
    def later(f: JsObject) = Future(Some(f))

    preload match {
      case Me => request.userIdOpt.map(uid => later(userController.getUserInfo(uid))).getOrElse(none)
      case Prefs => none
      case RecommendedFriends => none
      case Stream => request.userIdOpt.map(getKeepsStream).getOrElse(none)
      case LeftHandRail => none
    }
  }

  private def getKeepsStream(userId: Id[User]): Future[Option[JsObject]] = { // Needs to stay in sync with KeepsController.getKeepStream
    keepCommander.getKeepStream(userId, 10, None, None, 8, sanitizeUrls = false, filterOpt = None).map { keeps =>
      Some(Json.obj("keeps" -> keeps))
    }
  }

  private def buildPayload(path: String, payload: JsObject) = {
    s"<script>preload('$path', ${Json.stringify(payload)});</script>"
  }

  private def noExplode[T](f: => Future[Option[T]]) = {
    def recoverAll[S](v: S): PartialFunction[Throwable, S] = {
      case ex: Throwable => log.warn("[SitePreloader] Fail", ex); v
    }
    Try(f.recover(recoverAll(None))).recover(recoverAll(Future.successful(None))).get
  }

}

sealed trait PreloadRequest {
  def path: String
}
sealed trait LoggedInPreloadRequest extends PreloadRequest
object PreloadRequest {
  val AllPages = Set(Me, Prefs)

  case object Me extends LoggedInPreloadRequest {
    val path = "/site/user/me"
  }
  case object Prefs extends LoggedInPreloadRequest {
    val path = "/site/user/prefs"
  }
  case object RecommendedFriends extends LoggedInPreloadRequest {
    val path = "/site/user/friends/recommended?offset=0&limit=10"
  }
  case object Stream extends LoggedInPreloadRequest {
    val path = "/site/keeps/stream?limit=10&beforeId=&afterId=&filterKind=&filterId=" // exactly what the app asks for
  }
  case object LeftHandRail extends LoggedInPreloadRequest {
    val path = "/site/keepableLibraries?includeOrgLibraries=true"
  }
}

object PreloadSet {
  type SP = Seq[PreloadRequest]
  import PreloadRequest._

  def apply(preloads: SP*)(implicit request: MaybeUserRequest[_]) = {
    val baseline = request match {
      case ur: UserRequest[_] =>
        (userLoggedIn +: preloads.collect { case lipr: LoggedInPreloadRequest => lipr }).flatten
      case nur: NonUserRequest[_] => empty
    }
    baseline.distinct
  }

  def userLoggedIn: SP = Seq(Me, Prefs)
  def userHome: SP = userLoggedIn ++ Seq(Stream)
  def library: SP = Seq()
  def team: SP = Seq()
  def keepPage: SP = Seq()

  def empty: SP = Seq()
}

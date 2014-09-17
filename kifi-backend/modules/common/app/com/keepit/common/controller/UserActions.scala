package com.keepit.common.controller

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.net.URI
import com.keepit.model.{ ExperimentType, KifiInstallation, User }
import play.api.mvc.{ Request, WrappedRequest, Controller, ActionBuilder, Result }

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

sealed trait MaybeUserRequest[T] extends Request[T]
case class NonUserRequest[T](request: Request[T]) extends WrappedRequest(request) with MaybeUserRequest[T]
// class PartialNonUserRequest[T](override val request: Request[T]) extends NonUserRequest(request) // todo(ray)
case class UserRequest[T](
    request: Request[T],
    userId: Id[User],
    userF: Future[User],
    experimentsF: Future[Set[ExperimentType]],
    kifiInstallationId: Option[ExternalId[KifiInstallation]] = None,
    adminUserId: Option[Id[User]] = None) extends WrappedRequest(request) with MaybeUserRequest[T] {
  lazy val user = Await.result(userF, 5 seconds)
  lazy val experiments = Await.result(experimentsF, 5 seconds)
}

trait UserActionsHelper {
  def getUserIdOpt(implicit request: Request[_]): Option[Id[User]] = {
    request.session.get(ActionAuthenticator.FORTYTWO_USER_ID).map(id => Id[User](id.toLong)) // check with mobile
  }

  def isAdmin(userId: Id[User]): Boolean

  def getUserOpt(implicit request: Request[_]): Future[Option[User]]

  def getUserExperiments(implicit request: Request[_]): Future[Set[ExperimentType]]
}

trait UserActions { self: Controller =>

  protected def userActionsHelper: UserActionsHelper

  private def buildUserRequest[A](userId: Id[User])(implicit request: Request[A]) =
    UserRequest(request, userId, userActionsHelper.getUserOpt.map(_.get), userActionsHelper.getUserExperiments)

  private def getOrigin[A](request: Request[A]): Option[String] = {
    request.headers.get("Origin").filter { uri =>
      val host = URI.parse(uri).toOption.flatMap(_.host).map(_.toString).getOrElse("")
      host.endsWith("ezkeep.com") || host.endsWith("kifi.com")
    }
  }

  private def maybeAugmentCORS[A](res: Result)(implicit request: Request[A]): Result = {
    getOrigin(request) map { h =>
      res.withHeaders(
        "Access-Control-Allow-Origin" -> h,
        "Access-Control-Allow-Credentials" -> "true"
      )
    } getOrElse res
  }

  private def maybeAugmentKcid[A](res: Result)(implicit request: Request[A]): Result = { // for campaign tracking
    request.queryString.get("kcid").flatMap(_.headOption).map { kcid =>
      res.addingToSession("kcid" -> kcid)(request)
    } getOrElse res
  }

  object UserAction extends ActionBuilder[UserRequest] {
    def invokeBlock[A](request: Request[A], block: (UserRequest[A]) => Future[Result]): Future[Result] = {
      implicit val req = request
      userActionsHelper.getUserIdOpt match {
        case Some(userId) =>
          block(buildUserRequest(userId)).map(maybeAugmentCORS(_))
        case None => Future.successful(Unauthorized)
      }
    }
  }

  object MaybeUserAction extends ActionBuilder[MaybeUserRequest] {
    def invokeBlock[A](request: Request[A], block: (MaybeUserRequest[A]) => Future[Result]): Future[Result] = {
      implicit val req = request
      userActionsHelper.getUserIdOpt match {
        case Some(userId) =>
          block(buildUserRequest(userId)).map(maybeAugmentCORS(_))
        case None =>
          block(NonUserRequest(request)).map(maybeAugmentKcid(_))
      }
    }
  }

}

package com.keepit.common.controller

import com.keepit.common.controller.FortyTwoCookies.{ ImpersonateCookie, KifiInstallationCookie }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.logging.Logging
import com.keepit.common.core._
import com.keepit.common.net.URI
import com.keepit.model.{ SocialUserInfo, ExperimentType, KifiInstallation, User }
import play.api.http.ContentTypes
import play.api.mvc._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import securesocial.core.{ UserService, SecureSocial, Identity }
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

sealed trait MaybeUserRequest[T] extends Request[T]
trait NonUserRequest[T] extends MaybeUserRequest[T]
trait UserRequest[T] extends MaybeUserRequest[T] {
  def request: Request[T]
  def userId: Id[User]
  def adminUserId: Option[Id[User]]
  def user: User
  def experiments: Set[ExperimentType]
}

trait SecureSocialIdentityAccess[T] { self: MaybeUserRequest[T] =>
  def identityOptF: () => Future[Option[Identity]] = () => Future.successful(None)
  lazy val identityOpt = Await.result(identityOptF.apply, 5 seconds)
}

case class SimpleNonUserRequest[T](request: Request[T]) extends WrappedRequest(request) with NonUserRequest[T] with SecureSocialIdentityAccess[T]

case class SimpleUserRequest[T](
    request: Request[T],
    userId: Id[User],
    adminUserId: Option[Id[User]],
    userF: () => Future[User],
    experimentsF: () => Future[Set[ExperimentType]],
    override val identityOptF: () => Future[Option[Identity]],
    kifiInstallationId: () => Option[ExternalId[KifiInstallation]]) extends WrappedRequest(request) with UserRequest[T] with SecureSocialIdentityAccess[T] {
  lazy val user = Await.result(userF.apply, 5 seconds)
  lazy val experiments = Await.result(experimentsF.apply, 5 seconds)
}

trait UserActionsRequirements {

  def buildNonUserRequest[A](implicit request: Request[A]): NonUserRequest[A]

  def kifiInstallationCookie: KifiInstallationCookie

  def impersonateCookie: ImpersonateCookie

  def isAdmin(userId: Id[User])(implicit request: Request[_]): Future[Boolean]

  def getUserOpt(userId: Id[User])(implicit request: Request[_]): Future[Option[User]]

  def getUserByExtIdOpt(extId: ExternalId[User]): Future[Option[User]]

  def getUserExperiments(userId: Id[User])(implicit request: Request[_]): Future[Set[ExperimentType]]

  def getSocialUserInfos(userId: Id[User]): Future[Seq[SocialUserInfo]]
}

trait UserActionsHelper extends UserActionsRequirements {

  def getUserIdOpt(implicit request: Request[_]): Option[Id[User]] = {
    request.session.get(ActionAuthenticator.FORTYTWO_USER_ID).map(id => Id[User](id.toLong)) // check with mobile
  }

  def getKifiInstallationIdOpt(implicit request: Request[_]): Option[ExternalId[KifiInstallation]] = {
    kifiInstallationCookie.decodeFromCookie(request.cookies.get(kifiInstallationCookie.COOKIE_NAME))
  }

  def getImpersonatedUserIdOpt(implicit request: Request[_]): Option[ExternalId[User]] = {
    impersonateCookie.decodeFromCookie(request.cookies.get(impersonateCookie.COOKIE_NAME))
  }

}

trait UserActions extends Logging { self: Controller =>

  protected def userActionsHelper: UserActionsHelper

  private def buildUserRequest[A](userId: Id[User], adminUserId: Option[Id[User]] = None)(implicit request: Request[A]): UserRequest[A] =
    SimpleUserRequest(
      request,
      userId,
      adminUserId,
      () => userActionsHelper.getUserOpt(userId).map(_.get),
      () => userActionsHelper.getUserExperiments(userId),
      () => userActionsHelper.getSocialUserInfos(userId).map(_.headOption.flatMap(_.credentials)),
      () => userActionsHelper.getKifiInstallationIdOpt
    )

  private def maybeAugmentCORS[A](res: Result)(implicit request: Request[A]): Result = {
    request.headers.get("Origin").filter { uri =>
      val host = URI.parse(uri).toOption.flatMap(_.host).map(_.toString).getOrElse("")
      host.endsWith("ezkeep.com") || host.endsWith("kifi.com")
    } map { h =>
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

  private def impersonate[A](adminUserId: Id[User], impersonateExtId: ExternalId[User])(implicit request: Request[A]): Future[(UserRequest[A], Id[User])] = {
    userActionsHelper.isAdmin(adminUserId) flatMap { isAdmin =>
      if (!isAdmin) throw new IllegalStateException(s"non admin user $adminUserId tries to impersonate to $impersonateExtId")
      userActionsHelper.getUserByExtIdOpt(impersonateExtId) map { impUserOpt =>
        val impUserId = impUserOpt.get.id.get // fail hard
        log.info(s"[impersonate] admin user $adminUserId is impersonating user $impUserId with request ${request.path}")
        buildUserRequest(impUserId, Some(adminUserId)) -> impUserId
      }
    }
  }

  private def buildUserAction[A](userId: Id[User], block: (UserRequest[A]) => Future[Result])(implicit request: Request[A]): Future[Result] = {
    val resF = userActionsHelper.getImpersonatedUserIdOpt match {
      case Some(impExtId) =>
        impersonate(userId, impExtId).flatMap {
          case (req, impUserId) =>
            block(req).map { res =>
              res.withSession(request.session + ActionAuthenticator.FORTYTWO_USER_ID -> impUserId.toString)
            }
        }
      case None =>
        block(buildUserRequest(userId))
    }
    resF.map(maybeAugmentCORS(_))
  }

  private def pageAction[P[_]] = new ActionFunction[P, P] {
    def invokeBlock[A](request: P[A], block: (P[A]) => Future[Result]): Future[Result] = {
      block(request).map(_.withHeaders(CONTENT_TYPE -> HTML))
    }
  }

  object UserAction extends ActionBuilder[UserRequest] {
    def invokeBlock[A](request: Request[A], block: (UserRequest[A]) => Future[Result]): Future[Result] = {
      implicit val req = request
      userActionsHelper.getUserIdOpt match {
        case Some(userId) => buildUserAction(userId, block)
        case None => Future.successful(Forbidden)
      }
    }
  }
  val UserPage = (UserAction andThen pageAction)

  object MaybeUserAction extends ActionBuilder[MaybeUserRequest] {
    def invokeBlock[A](request: Request[A], block: (MaybeUserRequest[A]) => Future[Result]): Future[Result] = {
      implicit val req = request
      userActionsHelper.getUserIdOpt match {
        case Some(userId) => buildUserAction(userId, block)
        case None => block(userActionsHelper.buildNonUserRequest).map(maybeAugmentKcid(_))
      }
    }
  }
  val MaybeUserPage = (MaybeUserAction andThen pageAction)

  private object AdminCheck extends ActionFilter[UserRequest] {
    protected def filter[A](request: UserRequest[A]): Future[Option[Result]] = {
      if (request.adminUserId.isDefined) Future.successful(None)
      else userActionsHelper.isAdmin(request.userId)(request) map { isAdmin =>
        if (isAdmin) None
        else Some(Forbidden) tap { res => log.warn(s"[AdminCheck] User ${request.userId} is denied access to ${request.path}") }
      }
    }
  }

  val AdminUserAction = (UserAction andThen AdminCheck)
  val AdminUserPage = (AdminUserAction andThen pageAction)

}

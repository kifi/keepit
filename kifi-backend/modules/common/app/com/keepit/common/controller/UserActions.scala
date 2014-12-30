package com.keepit.common.controller

import com.keepit.common.controller.FortyTwoCookies.{ ImpersonateCookie, KifiInstallationCookie }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.common.logging.Logging
import com.keepit.common.core._
import com.keepit.common.net.URI
import com.keepit.model.{ ExperimentType, KifiInstallation, User }
import play.api.Play
import play.api.libs.iteratee.Iteratee
import play.api.mvc._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import securesocial.core.{ UserService, SecureSocial, Identity }
import scala.concurrent.duration._
import scala.concurrent.{ Promise, Await, Future }
import scala.util.{ Failure, Success, Try }

sealed trait MaybeUserRequest[T] extends Request[T] {
  // for backward compatibility only; use UserRequest/NonUserRequest where possible
  def userIdOpt: Option[Id[User]] = this match {
    case ur: UserRequest[T] => Some(ur.userId)
    case _ => None
  }
  def userOpt: Option[User] = this match {
    case ur: UserRequest[T] => Some(ur.user)
    case _ => None
  }
  def identityOpt: Option[Identity] = this match {
    case ur: UserRequest[T] => ur.identityOpt
    case nr: NonUserRequest[_] => nr.identityOpt
  }
}

case class NonUserRequest[T](request: Request[T], private val identityF: () => Option[Identity] = () => None) extends WrappedRequest[T](request) with MaybeUserRequest[T] with SecureSocialIdentityAccess[T] {
  override def identityOpt: Option[Identity] = identityF.apply()
}

case class UserRequest[T](request: Request[T], userId: Id[User], adminUserId: Option[Id[User]], helper: UserActionsHelper) extends WrappedRequest[T](request) with MaybeUserRequest[T] with SecureSocialIdentityAccess[T] with MaybeCostlyUserAttributes[T] {
  implicit val req = request

  private class Lazily[A](f: => Future[A]) {
    private lazy val cachedF: Future[A] = f
    private lazy val cachedV = Await.result(cachedF, 5 seconds)
    def get: Future[A] = cachedF
    def awaitGet: A = cachedV
  }

  private val user0: Lazily[User] = new Lazily(helper.getUserOpt(userId).map(_.get))
  def userF = user0.get
  def user = user0.awaitGet

  private val experiments0: Lazily[Set[ExperimentType]] = new Lazily(helper.getUserExperiments(userId))
  def experimentsF = experiments0.get
  def experiments = experiments0.awaitGet

  private val identityOpt0: Lazily[Option[Identity]] = new Lazily(helper.getSecureSocialIdentityOpt(userId))
  def identityOptF = identityOpt0.get
  override def identityOpt = identityOpt0.awaitGet

  lazy val kifiInstallationId: Option[ExternalId[KifiInstallation]] = helper.getKifiInstallationIdOpt
}

// for backward-compatibility
trait MaybeCostlyUserAttributes[T] { self: UserRequest[T] =>
  def user: User
  def experiments: Set[ExperimentType]
  def kifiInstallationId: Option[ExternalId[KifiInstallation]]
}

// for backward-compatibility
trait SecureSocialIdentityAccess[T] { self: MaybeUserRequest[T] =>
  def identityOpt: Option[Identity]
}

trait UserActionsRequirements {

  def airbrake: AirbrakeNotifier

  def buildNonUserRequest[A](implicit request: Request[A]): NonUserRequest[A] = NonUserRequest(request)

  def kifiInstallationCookie: KifiInstallationCookie

  def impersonateCookie: ImpersonateCookie

  def isAdmin(userId: Id[User])(implicit request: Request[_]): Future[Boolean]

  def getUserOpt(userId: Id[User])(implicit request: Request[_]): Future[Option[User]]

  def getUserByExtIdOpt(extId: ExternalId[User]): Future[Option[User]]

  def getUserExperiments(userId: Id[User])(implicit request: Request[_]): Future[Set[ExperimentType]]

  def getSecureSocialIdentityFromRequest(implicit request: Request[_]): Future[Option[Identity]]

  def getUserIdOptFromSecureSocialIdentity(identity: Identity): Future[Option[Id[User]]]

  def getSecureSocialIdentityOpt(userId: Id[User])(implicit request: Request[_]): Future[Option[Identity]]

}

trait SecureSocialHelper extends Logging {
  def getSecureSocialUserFromRequest(implicit request: Request[_]): Option[Identity] = {
    try {
      val maybeAuthenticator = SecureSocial.authenticatorFromRequest
      log.info(s"[getSecureSocialUserFromRequest] identityId=${maybeAuthenticator.map(_.identityId)} maybeAuthenticator=$maybeAuthenticator")
      maybeAuthenticator flatMap { authenticator =>
        UserService.find(authenticator.identityId) tap { u => log.info(s"[getSecureSocialUserFromRequest] authenticator=${authenticator.id} identityId=${authenticator.identityId} user=${u.map(_.email)}") }
      }
    } catch {
      case t: Throwable =>
        log.error(s"[getSecureSocialUserFromRequest] Caught exception $t; cause=${t.getCause}", t)
        None
    }
  }
}

import KifiSession._

trait UserActionsHelper extends UserActionsRequirements with Logging {

  def getUserIdFromSession(implicit request: Request[_]): Try[Option[Id[User]]] =
    Try {
      Play.maybeApplication.flatMap { _ => request.session.getUserId }
    }

  def getUserIdOptWithFallback(implicit request: Request[_]): Future[Option[Id[User]]] = {
    val kifiIdOpt = getUserIdFromSession.recover {
      case t: Throwable =>
        airbrake.notify(s"[getUserIdOpt] Caught exception $t while retrieving userId from request; cause=${t.getCause}. Path: ${request.path}, headers: ${request.headers.toMap}", t)
        None
    }.get

    kifiIdOpt match {
      case Some(userId) =>
        Future.successful(Some(userId))
      case None =>
        getSecureSocialIdentityFromRequest.flatMap { identityOpt =>
          identityOpt match {
            case None => Future.successful(None)
            case Some(identity) => getUserIdOptFromSecureSocialIdentity(identity)
          }
        }
    }
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
    UserRequest(request, userId, adminUserId, userActionsHelper)

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
    Play.maybeApplication.map { app =>
      request.queryString.get("kcid").flatMap(_.headOption).map { kcid =>
        res.addingToSession("kcid" -> kcid)(request)
      } getOrElse {
        val referrer: String = request.headers.get("Referer").flatMap { ref =>
          URI.parse(ref).toOption.flatMap(_.host).map(_.name)
        } getOrElse ("na")
        request.session.get("kcid").map { existingKcid =>
          if (existingKcid.startsWith("organic") && !referrer.contains("kifi.com")) {
            res.addingToSession("kcid" -> s"na-organic-$referrer")(request)
          } else res
        } getOrElse {
          res.addingToSession("kcid" -> s"na-organic-$referrer")(request)
        }
      }
    } getOrElse res
  }

  private def maybeSetUserIdInSession[A](userId: Id[User], res: Result)(implicit request: Request[A]): Result = {
    Play.maybeApplication.map { app =>
      userActionsHelper.getUserIdFromSession match {
        case Success(Some(id)) if id == userId => res
        case Success(_) => res.withSession(res.session.setUserId(userId))
        case Failure(t) =>
          log.error(s"[maybeSetUserIdInSession($userId)] Caught exception while retrieving userId from kifi cookie", t)
          res.withSession(res.session.setUserId(userId))
      }
    } getOrElse res
  }

  private def impersonate[A](adminUserId: Id[User], impersonateExtId: ExternalId[User])(implicit request: Request[A]): Future[UserRequest[A]] = {
    userActionsHelper.isAdmin(adminUserId) flatMap { isAdmin =>
      if (!isAdmin) throw new IllegalStateException(s"non admin user $adminUserId tries to impersonate to $impersonateExtId")
      userActionsHelper.getUserByExtIdOpt(impersonateExtId) map { impUserOpt =>
        val impUserId = impUserOpt.get.id.get // fail hard
        log.info(s"[impersonate] admin user $adminUserId is impersonating user $impUserId with request ${request.path}")
        buildUserRequest(impUserId, Some(adminUserId))
      }
    }
  }

  private def buildUserAction[A](userId: Id[User], block: (UserRequest[A]) => Future[Result])(implicit request: Request[A]): Future[Result] = {
    userActionsHelper.getImpersonatedUserIdOpt match {
      case Some(impExtId) =>
        impersonate(userId, impExtId).flatMap { userRequest =>
          block(userRequest).map(maybeSetUserIdInSession(userId, _))
        }
      case None =>
        block(buildUserRequest(userId)).map(maybeSetUserIdInSession(userId, _))
    }
  }

  object UserAction extends ActionBuilder[UserRequest] {
    def invokeBlock[A](request: Request[A], block: (UserRequest[A]) => Future[Result]): Future[Result] = {
      implicit val req = request
      val result = userActionsHelper.getUserIdOptWithFallback flatMap { userIdOpt =>
        userIdOpt match {
          case Some(userId) => buildUserAction(userId, block)
          case None => Future.successful(Forbidden) tap { _ => log.warn(s"[UserAction] Failed to retrieve userId for request=$request; headers=${request.headers.toMap}") }
        }
      }
      result.map(maybeAugmentCORS(_))
    }
  }

  object UserPage extends ActionBuilder[UserRequest] {
    def invokeBlock[A](request: Request[A], block: (UserRequest[A]) => Future[Result]) = {
      implicit val req = request
      UserAction.invokeBlock(request, block).map {
        case result if result.header.status == FORBIDDEN =>
          val nRes = Redirect("/login")
          // Less than ideal, but we can't currently test this:
          Play.maybeApplication match {
            case Some(_) => nRes.withSession(result.session + ("original-url" -> request.uri))
            case None => nRes
          }
        case result =>
          result
      }
    }
  }

  object MaybeUserAction extends ActionBuilder[MaybeUserRequest] {
    def invokeBlock[A](request: Request[A], block: (MaybeUserRequest[A]) => Future[Result]): Future[Result] = {
      implicit val req = request
      val result = userActionsHelper.getUserIdOptWithFallback flatMap { userIdOpt =>
        userIdOpt match {
          case Some(userId) => buildUserAction(userId, block)
          case None => block(userActionsHelper.buildNonUserRequest).map(maybeAugmentKcid(_))
        }
      }
      result.map(maybeAugmentCORS(_))
    }
  }

  val MaybeUserPage = MaybeUserAction // currently we don't have situations when Forbidden means they need to log in for a MaybeUserPage

}

trait AdminUserActions extends UserActions with ShoeboxServiceController {

  private object AdminCheck extends ActionFilter[UserRequest] {
    protected def filter[A](request: UserRequest[A]): Future[Option[Result]] = {
      if (request.adminUserId.exists(id => Await.result(userActionsHelper.isAdmin(id)(request), 5 seconds))
        || (Play.maybeApplication.exists(Play.isDev(_) && request.userId.id == 1L))) Future.successful(None)
      else userActionsHelper.isAdmin(request.userId)(request) map { isAdmin =>
        if (isAdmin) None
        else Some(Forbidden) tap { res => log.warn(s"[AdminCheck] User ${request.userId} is denied access to ${request.path}") }
      }
    }
  }

  val AdminUserAction = (UserAction andThen AdminCheck)
  val AdminUserPage = AdminUserAction // not forwarding user when the hit an admin page when not logged in is okay

}

object KifiSession {

  val FORTYTWO_USER_ID = "fortytwo_user_id"

  implicit class HttpSessionWrapper(val underlying: Session) extends AnyVal {
    def setUserId(userId: Id[User]): Session = underlying + (FORTYTWO_USER_ID -> userId.id.toString)
    def getUserId(): Option[Id[User]] = underlying.get(FORTYTWO_USER_ID) map parseUserId
    def parseUserId(id: String): Id[User] = {
      val idInt = try {
        id.toLong
      } catch {
        //There's some bad sessions out there that contains the id in the format of "Some(1234)" instead of just "1234". This is an attempt to let these ids through in spite of the bad formatting.
        case e: NumberFormatException =>
          """Some\(([0-9]+)\)""".r.findAllMatchIn(id).map(_.group(1)).toSeq.headOption.map(_.toLong).getOrElse(throw new Exception(s"can't parse id $id", e))
      }
      Id[User](idInt)
    }
    def deleteUserId(): Session = underlying - FORTYTWO_USER_ID
  }

}

case class ReportedException(id: ExternalId[AirbrakeError], cause: Throwable) extends Exception(id.toString, cause)

package com.keepit.common.controller

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.controller.FortyTwoCookies.{ ImpersonateCookie, KifiInstallationCookie }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.view.UserSessionView
import com.keepit.model.{ User, UserExperimentType }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.shoebox.model.ids.UserSessionExternalId
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.RequestHeader
import securesocial.core.{ IdentityId }

import scala.concurrent.Future
import scala.util.Try

@Singleton
class RemoteUserActionsHelper @Inject() (
    val airbrake: AirbrakeNotifier,
    shoebox: ShoeboxServiceClient,
    userExperimentCommander: RemoteUserExperimentCommander,
    val impersonateCookie: ImpersonateCookie,
    val kifiInstallationCookie: KifiInstallationCookie) extends UserActionsHelper {

  def isAdmin(userId: Id[User]): Future[Boolean] = getUserExperiments(userId).map(_.contains(UserExperimentType.ADMIN))

  def getUserOpt(userId: Id[User]): Future[Option[User]] = shoebox.getUser(userId)

  def getUserByExtIdOpt(extId: ExternalId[User]): Future[Option[User]] = shoebox.getUserOpt(extId)

  def getUserExperiments(userId: Id[User]): Future[Set[UserExperimentType]] = userExperimentCommander.getExperimentsByUser(userId)

  def getUserIdOptWithFallback(implicit request: RequestHeader): Future[Option[Id[User]]] = {
    // 3 ways we could figure out who this user is:
    // • Play session exists, and has a userId. Super fast.
    // • SecureSocial cookie exists, containing sessionId. Look it up on shoebox.
    // • sessionId was passed in directly. Look it up on shoebox.
    request.session.get(KifiSession.FORTYTWO_USER_ID).map { userIdStr =>
      Future.successful(Try(userIdStr.toLong).map(Id[User](_)).toOption)
    }.getOrElse {
      getIdentityIdFromRequestAsync(request).flatMap {
        case Some(identityId) =>
          shoebox.getUserIdByIdentityId(identityId).map {
            case Some(userId) => Some(userId)
            case None => log.warn(s"[getUserIdFromRequest] Auth exists, no userId in identity. ${identityId.providerId} :: ${identityId.userId}."); None
          }
        case None =>
          log.info(s"[getUserIdFromRequest] Refusing auth because not valid: ${request.headers.toSimpleMap.toString}")
          Future.successful(None)
      }
    }
  }

  private def getIdentityIdFromRequestAsync(implicit request: RequestHeader): Future[Option[IdentityId]] = {
    getSessionIdFromRequest(request).map { sid =>
      getSessionByExternalId(sid).map {
        case Some(session) if session.valid =>
          Some(IdentityId(session.socialId.id, session.provider.name))
        case otherwise =>
          log.info(s"[getIdentityIdFromRequestAsync] Refusing auth because not valid: ${request.headers.toSimpleMap.toString}")
          None
      }
    }.getOrElse {
      log.warn(s"[getIdentityIdFromRequestAsync] Could not find user. ${request.headers.toSimpleMap.toString}")
      Future.successful(None)
    }
  }

  def getSessionByExternalId(sessionId: UserSessionExternalId): Future[Option[UserSessionView]] = {
    shoebox.getSessionByExternalId(sessionId).map {
      case Some(session) if session.valid =>
        Some(session)
      case otherwise =>
        log.info(s"[getIdentityIdFromRequestAsync] Refusing auth because not valid: $sessionId $otherwise")
        None
    }
  }

}

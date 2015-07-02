package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model._
import play.api.http.Status._

@ImplementedBy(classOf[LibraryMembershipCommanderImpl])
trait LibraryMembershipCommander {
  def updateMembership(requestorId: Id[User], request: ModifyLibraryMembershipRequest): Either[LibraryFail, LibraryMembership]
}

case class ModifyLibraryMembershipRequest(userId: Id[User], libraryId: Id[Library],
  subscription: Option[Boolean] = None,
  starred: Option[LibraryPriority] = None,
  listed: Option[Boolean] = None,
  access: Option[LibraryAccess] = None)

@Singleton
class LibraryMembershipCommanderImpl @Inject() (
    db: Database,
    libraryMembershipRepo: LibraryMembershipRepo) extends LibraryMembershipCommander with Logging {

  def updateMembership(requestorId: Id[User], request: ModifyLibraryMembershipRequest): Either[LibraryFail, LibraryMembership] = {
    val (requestorMembership, targetMembershipOpt) = db.readOnlyMaster { implicit s =>
      val requestorMembership = libraryMembershipRepo.getWithLibraryIdAndUserId(request.libraryId, requestorId)
      val targetMembershipOpt = libraryMembershipRepo.getWithLibraryIdAndUserId(request.libraryId, request.userId)
      (requestorMembership, targetMembershipOpt)
    }
    (for {
      requester <- requestorMembership
      targetMembership <- targetMembershipOpt
    } yield {
      def modifySelfCheck[T](valueOpt: Option[T], fallback: => T): Either[LibraryFail, T] = {
        valueOpt match {
          case Some(value) =>
            if (requestorId == targetMembership.userId) {
              Right(value)
            } else {
              Left(LibraryFail(BAD_REQUEST, "permission_denied"))
            }
          case None => Right(fallback)
        }
      }

      def canChangeAccess(accessOpt: Option[LibraryAccess]) = {
        accessOpt match {
          case Some(access) =>
            access match {
              case LibraryAccess.READ_WRITE | LibraryAccess.READ_ONLY if (requester.isOwner && targetMembership.access != LibraryAccess.OWNER) =>
                Right(access)
              case _ => Left(LibraryFail(BAD_REQUEST, "permission_denied"))
            }
          case None => Right(targetMembership.access)
        }
      }

      for {
        starred <- modifySelfCheck[LibraryPriority](request.starred, targetMembership.starred).right
        subscribed <- modifySelfCheck(request.subscription, targetMembership.subscribedToUpdates).right
        isListed <- modifySelfCheck(request.listed, targetMembership.listed).right
        access <- canChangeAccess(request.access).right
      } yield {
        val modifiedLib = targetMembership.copy(starred = starred, subscribedToUpdates = subscribed, listed = isListed, access = access)
        if (targetMembership != modifiedLib) {
          db.readWrite { implicit session =>
            libraryMembershipRepo.save(modifiedLib)
          }
        } else {
          targetMembership
        }
      }
    }).getOrElse(Left(LibraryFail(BAD_REQUEST, "permission_denied")))
  }
}

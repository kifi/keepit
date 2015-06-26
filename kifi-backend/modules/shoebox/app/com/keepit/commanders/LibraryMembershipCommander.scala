package com.keepit.commanders

import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model._
import play.api.http.Status._

@ImplementedBy(classOf[LibraryMembershipCommanderImpl])
trait LibraryMembershipCommander {
  def updateMembership(userId: Id[User], libraryId: Id[Library], request: ModifyLibraryMembershipRequest): Either[LibraryFail, LibraryMembership]
}

case class ModifyLibraryMembershipRequest(subscribedToUpdatesNew: Option[Boolean] = None, isStarred: Option[Boolean] = None)

class LibraryMembershipCommanderImpl @Inject() (
    db: Database,
    libraryMembershipRepo: LibraryMembershipRepo) extends LibraryMembershipCommander with Logging {

  type LibraryModification = (LibraryMembership) => LibraryMembership

  def updateMembership(userId: Id[User], libraryId: Id[Library], request: ModifyLibraryMembershipRequest): Either[LibraryFail, LibraryMembership] = {
    val libMembership = db.readOnlyMaster { implicit s =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId)
    }
    val modifications: LibraryModification = request.isStarred match {
      case Some(starred) => (m) => m.copy(starred = starred)
      case None => (m) => m
    }
    libMembership match {
      case None => Left(LibraryFail(NOT_FOUND, "not_a_member"))
      case Some(mem) =>
        val modifiedLib = modifications(mem)
        if (mem != modifiedLib) {
          Right(db.readWrite { implicit session =>
            libraryMembershipRepo.save(modifiedLib)
          })
        } else {
          Right(mem)
        }
    }
  }

  private def updateStarredLibrary(userId: Id[User], libraryId: Id[Library], isStarred: Boolean): Either[LibraryFail, LibraryMembership] = {
    db.readOnlyMaster { implicit s =>
      libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId)
    } match {
      case None => Left(LibraryFail(NOT_FOUND, "need_to_follow_to_star"))
      case Some(mem) if mem.starred == isStarred => Right(mem)
      case Some(mem) => {
        val updatedMembership = db.readWrite { implicit s =>
          libraryMembershipRepo.save(mem.copy(starred = isStarred))
        }
        Right(updatedMembership)
      }
    }
  }
}

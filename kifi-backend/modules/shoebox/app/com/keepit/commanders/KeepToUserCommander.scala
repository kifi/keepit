package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model._
import org.joda.time.DateTime

import scala.util.control.NoStackTrace
import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[KeepToUserCommanderImpl])
trait KeepToUserCommander {
  def internKeepInUser(keep: Keep, userId: Id[User], addedBy: Option[Id[User]], addedAt: Option[DateTime] = None)(implicit session: RWSession): KeepToUser
  def removeKeepFromUser(keepId: Id[Keep], userId: Id[User])(implicit session: RWSession): Boolean
  def removeKeepFromAllUsers(keep: Keep)(implicit session: RWSession): Unit

  def deactivate(ktu: KeepToUser)(implicit session: RWSession): Unit

  // Fun helper methods
  def isKeepInUser(keepId: Id[Keep], userId: Id[User])(implicit session: RSession): Boolean
  def syncKeep(keep: Keep)(implicit session: RWSession): Unit
  def syncWithKeep(ktu: KeepToUser, keep: Keep)(implicit session: RWSession): KeepToUser
  def syncAndDeleteKeep(keep: Keep)(implicit session: RWSession): Unit
}

@Singleton
class KeepToUserCommanderImpl @Inject() (
  clock: Clock,
  ktuRepo: KeepToUserRepo)
    extends KeepToUserCommander with Logging {

  def internKeepInUser(keep: Keep, userId: Id[User], addedBy: Option[Id[User]], addedAt: Option[DateTime] = None)(implicit session: RWSession): KeepToUser = {
    ktuRepo.getByKeepIdAndUserId(keep.id.get, userId, excludeStateOpt = None) match {
      case Some(existingKtu) if existingKtu.isActive => existingKtu
      case existingKtuOpt =>
        val newKtuTemplate = KeepToUser(
          keepId = keep.id.get,
          userId = userId,
          addedBy = addedBy,
          addedAt = addedAt getOrElse clock.now,
          uriId = keep.uriId,
          lastActivityAt = keep.lastActivityAt
        )
        ktuRepo.save(newKtuTemplate.copy(id = existingKtuOpt.map(_.id.get)))
    }
  }

  def deactivate(ktu: KeepToUser)(implicit session: RWSession): Unit = {
    ktuRepo.deactivate(ktu)
  }
  def removeKeepFromUser(keepId: Id[Keep], userId: Id[User])(implicit session: RWSession): Boolean = {
    ktuRepo.getByKeepIdAndUserId(keepId, userId) match {
      case None => false
      case Some(activeKtu) =>
        deactivate(activeKtu)
        true
    }
  }
  def removeKeepFromAllUsers(keep: Keep)(implicit session: RWSession): Unit = {
    ktuRepo.getAllByKeepId(keep.id.get).foreach(deactivate)
  }

  def isKeepInUser(keepId: Id[Keep], userId: Id[User])(implicit session: RSession): Boolean = {
    ktuRepo.getByKeepIdAndUserId(keepId, userId).isDefined
  }

  def syncKeep(keep: Keep)(implicit session: RWSession): Unit = {
    // Sync ALL of the connections (including the dead ones)
    ktuRepo.getAllByKeepId(keep.id.get, excludeStateOpt = None).foreach { ktu => syncWithKeep(ktu, keep) }
  }
  def syncWithKeep(ktu: KeepToUser, keep: Keep)(implicit session: RWSession): KeepToUser = {
    require(ktu.keepId == keep.id.get, "keep.id does not match ktu.keepId")
    ktuRepo.save(ktu.withUriId(keep.uriId).withLastActivityAt(keep.lastActivityAt))
  }

  def syncAndDeleteKeep(keep: Keep)(implicit session: RWSession): Unit = {
    ktuRepo.getAllByKeepId(keep.id.get, excludeStateOpt = None).foreach { ktu =>
      ktuRepo.deactivate(ktu.withUriId(keep.uriId).withLastActivityAt(keep.lastActivityAt))
    }
  }
}

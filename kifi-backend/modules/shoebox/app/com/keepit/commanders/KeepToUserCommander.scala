package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model._

import scala.util.control.NoStackTrace
import scala.util.{ Failure, Success, Try }

sealed abstract class KeepToUserFail(val message: String) extends Exception(message) with NoStackTrace
object KeepToUserFail {
  case object NOT_CONNECTED_TO_USER extends KeepToUserFail("keep_not_connected_to_user")
}

@ImplementedBy(classOf[KeepToUserCommanderImpl])
trait KeepToUserCommander {
  def internKeepInUser(keep: Keep, userId: Id[User], addedBy: Id[User])(implicit session: RWSession): KeepToUser
  def removeKeepFromUser(keepId: Id[Keep], userId: Id[User])(implicit session: RWSession): Try[Unit]
  def removeKeepFromAllUsers(keep: Keep)(implicit session: RWSession): Unit

  // Fun helper methods
  def isKeepInUser(keepId: Id[Keep], userId: Id[User])(implicit session: RSession): Boolean
  def syncKeep(keep: Keep)(implicit session: RWSession): Unit
}

@Singleton
class KeepToUserCommanderImpl @Inject() (
  db: Database,
  clock: Clock,
  ktuRepo: KeepToUserRepo)
    extends KeepToUserCommander with Logging {

  def internKeepInUser(keep: Keep, userId: Id[User], addedBy: Id[User])(implicit session: RWSession): KeepToUser = {
    ktuRepo.getByKeepIdAndUserId(keep.id.get, userId, excludeStateOpt = None) match {
      case Some(existingKtu) if existingKtu.isActive => existingKtu
      case existingKtuOpt =>
        val newKtuTemplate = KeepToUser(
          keepId = keep.id.get,
          userId = userId,
          addedBy = addedBy,
          addedAt = clock.now,
          uriId = keep.uriId
        )
        ktuRepo.save(newKtuTemplate.copy(id = existingKtuOpt.flatMap(_.id)))
    }
  }

  def removeKeepFromUser(keepId: Id[Keep], userId: Id[User])(implicit session: RWSession): Try[Unit] = {
    ktuRepo.getByKeepIdAndUserId(keepId, userId) match {
      case None => Failure(KeepToUserFail.NOT_CONNECTED_TO_USER)
      case Some(activeKtu) =>
        ktuRepo.deactivate(activeKtu)
        Success(())
    }
  }
  def removeKeepFromAllUsers(keep: Keep)(implicit session: RWSession): Unit = {
    ktuRepo.getAllByKeepId(keep.id.get).foreach(ktuRepo.deactivate)
  }

  def isKeepInUser(keepId: Id[Keep], userId: Id[User])(implicit session: RSession): Boolean = {
    ktuRepo.getByKeepIdAndUserId(keepId, userId).isDefined
  }

  def syncKeep(keep: Keep)(implicit session: RWSession): Unit = {
    // Sync ALL of the connections (including the dead ones)
    ktuRepo.getAllByKeepId(keep.id.get, excludeStateOpt = None).foreach { ktu => syncWithKeep(ktu, keep) }
  }
  private def syncWithKeep(ktu: KeepToUser, keep: Keep)(implicit session: RWSession): KeepToUser = {
    require(ktu.keepId == keep.id.get, "keep.id does not match ktu.keepId")
    ktuRepo.save(ktu.withUriId(keep.uriId))
  }
}

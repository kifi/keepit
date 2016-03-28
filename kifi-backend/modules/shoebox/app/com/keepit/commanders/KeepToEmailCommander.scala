package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.model._
import org.joda.time.DateTime

@ImplementedBy(classOf[KeepToEmailCommanderImpl])
trait KeepToEmailCommander {
  def internKeepInEmail(keep: Keep, emailAddress: EmailAddress, addedBy: Option[Id[User]], addedAt: Option[DateTime] = None)(implicit session: RWSession): KeepToEmail
  def removeKeepFromEmail(keepId: Id[Keep], emailAddress: EmailAddress)(implicit session: RWSession): Boolean
  def removeKeepFromAllEmails(keep: Keep)(implicit session: RWSession): Unit
  def deactivate(kte: KeepToEmail)(implicit session: RWSession): Unit

  // Fun helper methods
  def syncKeep(keep: Keep)(implicit session: RWSession): Unit
  def syncWithKeep(kte: KeepToEmail, keep: Keep)(implicit session: RWSession): KeepToEmail
  def syncAndDeleteKeep(keep: Keep)(implicit session: RWSession): Unit
}

@Singleton
class KeepToEmailCommanderImpl @Inject() (
  clock: Clock,
  kteRepo: KeepToEmailRepo)
    extends KeepToEmailCommander with Logging {

  def internKeepInEmail(keep: Keep, emailAddress: EmailAddress, addedBy: Option[Id[User]], addedAt: Option[DateTime] = None)(implicit session: RWSession): KeepToEmail = {
    kteRepo.getByKeepIdAndEmailAddress(keep.id.get, emailAddress, excludeStateOpt = None) match {
      case Some(existingKte) if existingKte.isActive => existingKte
      case existingKteOpt =>
        val newKteTemplate = KeepToEmail(
          keepId = keep.id.get,
          emailAddress = emailAddress,
          addedBy = addedBy,
          addedAt = addedAt getOrElse clock.now,
          uriId = keep.uriId,
          lastActivityAt = keep.lastActivityAt
        )
        kteRepo.save(newKteTemplate.copy(id = existingKteOpt.map(_.id.get)))
    }
  }

  def deactivate(kte: KeepToEmail)(implicit session: RWSession): Unit = {
    kteRepo.deactivate(kte)
  }
  def removeKeepFromEmail(keepId: Id[Keep], emailAddress: EmailAddress)(implicit session: RWSession): Boolean = {
    kteRepo.getByKeepIdAndEmailAddress(keepId, emailAddress) match {
      case None => false
      case Some(activeKte) =>
        deactivate(activeKte)
        true
    }
  }
  def removeKeepFromAllEmails(keep: Keep)(implicit session: RWSession): Unit = {
    kteRepo.getAllByKeepId(keep.id.get).foreach(deactivate)
  }

  def syncKeep(keep: Keep)(implicit session: RWSession): Unit = {
    // Sync ALL of the connections (including the dead ones)
    kteRepo.getAllByKeepId(keep.id.get, excludeStateOpt = None).foreach { kte => syncWithKeep(kte, keep) }
  }
  def syncWithKeep(kte: KeepToEmail, keep: Keep)(implicit session: RWSession): KeepToEmail = {
    require(kte.keepId == keep.id.get, "keep.id does not match kte.keepId")
    kteRepo.save(kte.withUriId(keep.uriId).withLastActivityAt(keep.lastActivityAt))
  }

  def syncAndDeleteKeep(keep: Keep)(implicit session: RWSession): Unit = {
    kteRepo.getAllByKeepId(keep.id.get, excludeStateOpt = None).foreach { kte =>
      kteRepo.deactivate(kte.withUriId(keep.uriId).withLastActivityAt(keep.lastActivityAt))
    }
  }
}

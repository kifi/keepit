package com.keepit.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.abook.model._
import com.keepit.common.db.Id
import com.keepit.model.{ ABookInfo, User }
import com.keepit.common.mail.BasicContact
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.abook.typeahead.EContactTypeahead

@Singleton
class ContactInterner @Inject() (
    emailAccountRepo: EmailAccountRepo,
    econtactRepo: EContactRepo,
    econtactTypeahead: EContactTypeahead,
    db: Database) extends Logging {

  def internContact(userId: Id[User], abookId: Id[ABookInfo], contact: BasicContact): EContact = {
    val (econtact, inserted, updated) = db.readWrite { implicit session =>
      econtactRepo.getByAbookIdAndEmail(abookId, contact.email) match {
        case None => (insertNewContact(userId, abookId, contact), true, false)
        case Some(existingContact) => updateExistingContact(userId, existingContact, contact).map((_, false, true)) getOrElse (existingContact, false, false)
      }
    }
    if (inserted) { log.info(s"Inserted new contact $econtact for ABook $abookId of user $userId)") }
    if (updated) { log.info(s"Updated existing contact $econtact for ABook $abookId of user $userId)") }
    if (inserted || updated) { econtactTypeahead.refresh(userId) } // async
    econtact
  }

  def internContacts(userId: Id[User], abookId: Id[ABookInfo], contacts: Seq[BasicContact]): (Int, Int) = {
    val (inserted, updated) = db.readWrite { implicit session =>
      val existingContacts = econtactRepo.getByAbookId(abookId)
      val existingByLowerCasedAddress = existingContacts.map { contact => contact.email.address.toLowerCase -> contact }.toMap
      val toBeInternedByLowerCasedAddress = contacts.groupBy(_.email.address.toLowerCase)

      val (toBeUpdatedByLowerCasedAddress, toBeInsertedByLowerCasedAddress) = toBeInternedByLowerCasedAddress.partition {
        case (lowerCasedAddress, _) => existingByLowerCasedAddress.contains(lowerCasedAddress)
      }

      val inserted = insertNewContacts(userId, abookId, toBeInsertedByLowerCasedAddress)
      val updated = toBeUpdatedByLowerCasedAddress.count {
        case (lowerCasedAddress, contacts) =>
          val existingContact = existingByLowerCasedAddress(lowerCasedAddress)
          updateExistingContact(userId, existingContact, contacts: _*).isDefined
      }

      (inserted, updated)
    }

    if (inserted + updated > 0) { econtactTypeahead.refresh(userId) }
    log.info(s"Inserted $inserted new contacts and updated $updated existing contacts for ABook $abookId of user $userId")
    (inserted, updated)
  }

  private def updateExistingContact(userId: Id[User], existingContact: EContact, contacts: BasicContact*)(implicit session: RWSession): Option[EContact] = {
    if (existingContact.userId != userId) { throw new IllegalArgumentException(s"Existing EContact $existingContact should belong to user $userId.") }
    existingContact.updateWith(contacts: _*).copy(state = EContactStates.ACTIVE) match {
      case modifiedContact if modifiedContact != existingContact => Some(econtactRepo.save(modifiedContact))
      case _ => None
    }
  }

  private def insertNewContact(userId: Id[User], abookId: Id[ABookInfo], contact: BasicContact)(implicit session: RWSession): EContact = {
    val emailAccount = emailAccountRepo.internByAddress(contact.email)
    val newContact = EContact.make(userId, abookId, emailAccount, contact)
    econtactRepo.save(newContact)
  }

  private def insertNewContacts(userId: Id[User], abookId: Id[ABookInfo], toBeInsertedByLowerCasedAddress: Map[String, Seq[BasicContact]])(implicit session: RWSession): Int = if (toBeInsertedByLowerCasedAddress.isEmpty) 0 else {
    val uniqueEmailAddresses = toBeInsertedByLowerCasedAddress.map {
      case (lowerCasedAddress, contacts) =>
        val preferredContact = contacts.find(_.email.address == lowerCasedAddress) getOrElse contacts.head // arbitrary preference for lower cased addresses
        preferredContact.email
    }.toSeq
    val emailAccountsByLowerCasedAddress = emailAccountRepo.internByAddresses(uniqueEmailAddresses: _*).map {
      emailAccount => emailAccount.address.address.toLowerCase() -> emailAccount
    }.toMap
    val newEContacts = toBeInsertedByLowerCasedAddress.map {
      case (lowerCasedAddress, contacts) =>
        val emailAccount = emailAccountsByLowerCasedAddress(lowerCasedAddress)
        EContact.make(userId, abookId, emailAccount, contacts: _*)
    }.toSeq
    econtactRepo.insertAll(newEContacts)
  }
}

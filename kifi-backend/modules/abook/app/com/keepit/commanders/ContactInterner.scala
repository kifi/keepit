package com.keepit.commanders

import com.google.inject.{Singleton, Inject}
import com.keepit.abook.model.{EmailAccount, EmailAccountRepo}
import com.keepit.abook.{EContactRepo}
import com.keepit.common.db.Id
import com.keepit.model.{EContactStates, ABookInfo, EContact, User}
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
  db: Database
) extends Logging {

  def internContact(userId:Id[User], abookId: Id[ABookInfo], contact: BasicContact): EContact = {
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
      val toBeInternedByLowerCasedAddress = contacts.map { contact => contact.email.address.toLowerCase -> contact }.toMap

      val (toBeUpdatedByLowerCasedAddress, toBeInsertedByLowerCasedAddress) = toBeInternedByLowerCasedAddress.partition {
        case (lowerCasedAddress, _) => existingByLowerCasedAddress.contains(lowerCasedAddress)
      }

      val inserted = insertNewContacts(userId, abookId, toBeInsertedByLowerCasedAddress.values.toSeq)
      val updated = toBeUpdatedByLowerCasedAddress.count { case (lowerCasedAddress, contact) =>
        val existingContact = existingByLowerCasedAddress(lowerCasedAddress)
        updateExistingContact(userId, existingContact, contact).isDefined
      }

      (inserted, updated)
    }

    if (inserted + updated > 0) { econtactTypeahead.refresh(userId) }
    log.info(s"Inserted $inserted new contacts and updated $updated existing contacts for ABook $abookId of user $userId")
    (inserted, updated)
  }

  private def updateExistingContact(userId: Id[User], existingContact: EContact, contact: BasicContact)(implicit session: RWSession): Option[EContact] = {
    if (existingContact.userId != userId) { throw new IllegalArgumentException(s"Existing EContact $existingContact should belong to user $userId.") }
    existingContact.updateWith(contact).copy(state = EContactStates.ACTIVE) match {
      case modifiedContact if modifiedContact != existingContact => Some(econtactRepo.save(modifiedContact))
      case _ => None
    }
  }

  // todo(Léo): have email.id in EContact once EContact has been moved to ABook...
  private def insertNewContact(userId: Id[User], abookId: Id[ABookInfo], contact: BasicContact)(implicit session: RWSession): EContact = {
    val emailAccount = emailAccountRepo.internByAddress(contact.email)
    val newContact = makeNewContact(userId, abookId, emailAccount, contact)
    econtactRepo.save(newContact)
  }

  private def insertNewContacts(userId: Id[User], abookId: Id[ABookInfo], contacts: Seq[BasicContact])(implicit session: RWSession): Int = if (contacts.isEmpty) 0 else {
    val emailAccountsByLowerCasedAddress = emailAccountRepo.internByAddresses(contacts.map(_.email): _*).map {
      emailAccount => emailAccount.address.address.toLowerCase() -> emailAccount
    }.toMap
    val newContacts = contacts.map { contact =>
      val lowerCaseAddress = contact.email.address.toLowerCase()
      val emailAccount = emailAccountsByLowerCasedAddress(lowerCaseAddress)
      makeNewContact(userId, abookId, emailAccount, contact)
    }
    econtactRepo.insertAll(newContacts)
  }

  private def makeNewContact(userId: Id[User], abookId: Id[ABookInfo], emailAccount: EmailAccount, contact: BasicContact): EContact = {
    EContact(userId = userId, abookId = Some(abookId), email = emailAccount.address, contactUserId = emailAccount.userId).updateWith(contact)
  }
}

package com.keepit.model

import com.keepit.common.db.{CX, Entity, EntityTable, ExternalId, Id, State}
import com.keepit.common.time._
import java.sql.Connection
import org.joda.time.DateTime
import ru.circumflex.orm._
import com.keepit.common.mail.EmailAddressHolder

case class EmailAddress (
  id: Option[Id[EmailAddress]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  address: String
) extends EmailAddressHolder {
  def save(implicit conn: Connection): EmailAddress = {
    val entity = EmailAddressEntity(this.copy(updatedAt = currentDateTime))
    assert(1 == entity.save())
    entity.view
  }
  def sameAddress(otherAddress: String) = otherAddress == address
}

object EmailAddress {
  object States {
    val VERIFIED = State[EmailAddress]("verified")
    val UNVERIFIED = State[EmailAddress]("unverified")
  }
  
  def apply(addr: String): EmailAddress = EmailAddress(address = addr)
  
  def get(id: Id[EmailAddress])(implicit conn: Connection): EmailAddress = EmailAddressEntity.get(id).get.view
  
  def getByAddressOpt(address: String)(implicit conn: Connection): Option[EmailAddress] = 
    (EmailAddressEntity AS "e").map { e => SELECT (e.*) FROM e WHERE (e.address EQ address)}.unique.map(_.view)

}

private[model] class EmailAddressEntity extends Entity[EmailAddress, EmailAddressEntity] {
  val createdAt = "created_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val updatedAt = "updated_at".JODA_TIMESTAMP.NOT_NULL(currentDateTime)
  val address = "address".VARCHAR(512).NOT_NULL
  val userId = "user_id".ID[User]
  val state = "state".STATE.NOT_NULL(EmailAddress.States.UNVERIFIED)
  val verifiedAt = "verified_at".JODA_TIMESTAMP
  val lastVerificationSent = "last_verification_sent".JODA_TIMESTAMP
  
  def relation = EmailAddressEntity
  
  def view = EmailAddress(
    id = id.value,
    createdAt = createdAt(),
    updatedAt = updatedAt(),
    address = address()
  )
}

private[model] object EmailAddressEntity extends EmailAddressEntity with EntityTable[EmailAddress, EmailAddressEntity] {
  override def relationName = "email_address"
  
  def apply(view: EmailAddress): EmailAddressEntity = {
    val entity = new EmailAddressEntity
    entity.id.set(view.id)
    entity.createdAt := view.createdAt
    entity.updatedAt := view.updatedAt
    entity.address := view.address
    entity
  }
}

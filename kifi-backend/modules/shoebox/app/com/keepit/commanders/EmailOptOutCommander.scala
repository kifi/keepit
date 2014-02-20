package com.keepit.commanders

import com.keepit.common.crypto.SimpleDESCrypt
import com.google.inject.Inject
import com.keepit.common.mail.{OptoutSecret, GenericEmailAddress, EmailAddressHolder}
import scala.util.Try

class EmailOptOutCommander @Inject() (optoutSecret: OptoutSecret) {

  private val crypt = new SimpleDESCrypt
  private val key = crypt.stringToKey(optoutSecret.value)

  def generateOptOutToken(emailAddress: EmailAddressHolder) = {
    crypt.crypt(key, emailAddress.address)
  }
  def getEmailFromOptOutToken(optOutToken: String): Try[EmailAddressHolder] = {
    crypt.decrypt(key, optOutToken).map(GenericEmailAddress(_))
  }

}

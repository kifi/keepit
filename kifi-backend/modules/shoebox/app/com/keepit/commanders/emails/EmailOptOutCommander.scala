package com.keepit.commanders.emails

import com.keepit.common.crypto.RatherInsecureDESCrypt
import com.google.inject.Inject
import com.keepit.common.mail.{OptoutSecret, EmailAddress}
import scala.util.Try

class EmailOptOutCommander @Inject() (optoutSecret: OptoutSecret) {

  private val crypt = new RatherInsecureDESCrypt
  private val key = crypt.stringToKey(optoutSecret.value)

  def generateOptOutToken(emailAddress: EmailAddress) = {
    crypt.crypt(key, emailAddress.address)
  }

  def getEmailFromOptOutToken(optOutToken: String): Try[EmailAddress] = {
    crypt.decrypt(key, optOutToken).map(EmailAddress(_))
  }

}

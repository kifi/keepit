package com.keepit.commanders.emails

import com.keepit.common.crypto.RatherInsecureDESCrypt
import com.google.inject.{ Singleton, Inject }
import com.keepit.common.mail.{ OptoutSecret, EmailAddress }
import scala.util.Try

@Singleton
class EmailOptOutCommander @Inject() (optoutSecret: OptoutSecret) {

  private val crypt = new RatherInsecureDESCrypt
  private val key = crypt.stringToKey(optoutSecret.value)

  def generateOptOutToken(emailAddress: EmailAddress) = {
    val token = crypt.crypt(key, emailAddress.address)
    /* strip \r\n from tokens if they end with that */
    if (token.substring(token.size - 2) == "\r\n") token.substring(0, token.size - 2) else token
  }

  def getEmailFromOptOutToken(optOutToken: String): Try[EmailAddress] = {
    crypt.decrypt(key, optOutToken).map(EmailAddress(_))
  }

}

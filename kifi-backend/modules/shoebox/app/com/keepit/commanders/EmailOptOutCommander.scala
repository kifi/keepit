package com.keepit.commanders

import com.keepit.common.controller.WebsiteController
import play.api.Play.current
import play.api.mvc._
import com.keepit.model._
import com.keepit.common.db.slick._
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.crypto.SimpleDESCrypt
import com.google.inject.Inject
import com.keepit.common.mail.{PostOffice, GenericEmailAddress, EmailAddressHolder}
import scala.util.{Success, Try, Failure}
import play.api.data.Form
import play.api.data.Forms.{tuple, text, checked, optional}
import play.api.libs.json.Json
import com.keepit.common.KestrelCombinator

class EmailOptOutCommander @Inject() {

  private val crypt = new SimpleDESCrypt
  private val key = crypt.stringToKey(current.configuration.getString("optout.secret").get)

  def generateOptOutToken(emailAddress: EmailAddressHolder) = {
    crypt.crypt(key, emailAddress.address)
  }
  def getEmailFromOptOutToken(optOutToken: String): Try[EmailAddressHolder] = {
    crypt.decrypt(key, optOutToken).map(GenericEmailAddress(_))
  }

}

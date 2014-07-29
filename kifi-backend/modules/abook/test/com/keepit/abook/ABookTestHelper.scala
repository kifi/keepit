package com.keepit.abook

import com.google.inject.Inject
import com.keepit.abook.model.{ EmailAccountRepo, EContactRepo, EmailAccount, EContact }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.model.{ ABookOrigins, ABookOriginType, ABookInfo, User }
import com.keepit.test.DbInjectionHelper
import play.api.libs.json.Json
import com.keepit.abook.controllers.GmailABookOwnerInfo

trait ABookTestHelper {
  val u42 = Id[User](42)
  val c42 = Json.arr(
    Json.obj(
      "name" -> "foo bar",
      "firstName" -> "foo",
      "lastName" -> "bar",
      "emails" -> Seq("foo@42go.com", "bar@42go.com")),
    Json.obj(
      "name" -> "forty two",
      "firstName" -> "forty",
      "lastName" -> "two",
      "emails" -> Seq("fortytwo@42go.com", "Foo@42go.com ", "BAR@42go.com  ")),
    Json.obj(
      "name" -> "ray",
      "firstName" -> "ray",
      "lastName" -> "ng",
      "emails" -> Seq("ray@42go.com", " rAy@42GO.COM "))
  )

  val c53 = Json.arr(
    Json.obj(
      "name" -> "fifty three",
      "firstName" -> "fifty",
      "lastName" -> "three",
      "emails" -> Seq("fiftythree@53go.com"))
  )

  val iosUploadJson = Json.obj( // ios does not supply owner information
    "origin" -> "ios",
    "contacts" -> c42
  )

  val gmailOwner = GmailABookOwnerInfo(Some("123456789"), Some("42@42go.com"), /* Some(true), */ Some("42go.com"))
  val gmailUploadJson = Json.obj(
    "origin" -> "gmail",
    "ownerId" -> gmailOwner.id.get,
    "ownerEmail" -> gmailOwner.email.get,
    "contacts" -> c42
  )

  val gmailOwner2 = GmailABookOwnerInfo(Some("53"), Some("53@53go.com"), /* Some(true), */ Some("53.com"))
  val gmailUploadJson2 = Json.obj(
    "origin" -> "gmail",
    "ownerId" -> gmailOwner2.id.get,
    "ownerEmail" -> gmailOwner2.email.get,
    "contacts" -> c53
  )
}

object AbookTestEmails {
  val FOO_EMAIL = EmailAddress("foo@mail.com")
  val BAR_EMAIL = EmailAddress("bar@mail.com")
  val BAZ_EMAIL = EmailAddress("baz@mail.com")
}

case class ABookTestContactFactory @Inject() (
    econRepo: EContactRepo,
    accRepo: EmailAccountRepo,
    abookRepo: ABookInfoRepo)(implicit db: Database) {

  import AbookTestEmails.{ FOO_EMAIL, BAR_EMAIL, BAZ_EMAIL }

  def create(email: EmailAddress, contactEmail: EmailAddress): EContact =
    db.readWrite { implicit session =>
      val userId = Id[User](scala.util.Random.nextInt(9999999))
      val account = EmailAccount(address = email)
      val savedAccount = accRepo.save(account)
      val abook = ABookInfo(origin = ABookOrigins.GMAIL, userId = userId)
      val savedAbook = abookRepo.save(abook)
      val contact = EContact(
        userId = userId,
        email = contactEmail,
        abookId = savedAbook.id.get,
        emailAccountId = savedAccount.id.get)
      econRepo.save(contact)
    }

  def createMany = {
    val c1 = create(FOO_EMAIL, BAR_EMAIL)
    val c2 = create(BAR_EMAIL, BAZ_EMAIL)
    val c3 = create(BAZ_EMAIL, BAR_EMAIL)

    (c1, c2, c3)
  }
}


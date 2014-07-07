package com.keepit.controllers.mobile

import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.model.{EContact, User}

import org.specs2.mutable.Specification

import play.api.libs.json.Json

class MobileContactsControllerTest extends Specification {

  "MobileContactsController" should {
    "serialize a contact with a name" in {
      new MobileContactsController(null, null).serializeContact(
        EContact(
          id = Some(Id[EContact](8)),
          userId = Id[User](1),
          abookId = Some(Id(1)),
          email = EmailAddress("jim@davis.name"),
          name = Some("James R. Davis"),
          firstName = Some("Jim"),
          lastName = Some("Davis"))) ===
        Json.arr("jim@davis.name", "James R. Davis")
    }

    "serialize a contact with no name" in {
      new MobileContactsController(null, null).serializeContact(
        EContact(
          id = Some(Id[EContact](9)),
          userId = Id[User](1),
          abookId = Some(Id(1)),
          email = EmailAddress("bill@wattersons.org")
        )
      ) === Json.arr("bill@wattersons.org")
    }
  }

}

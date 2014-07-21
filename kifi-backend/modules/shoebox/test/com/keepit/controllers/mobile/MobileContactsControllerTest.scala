package com.keepit.controllers.mobile

import com.keepit.common.mail.EmailAddress

import org.specs2.mutable.Specification

import play.api.libs.json.Json
import com.keepit.abook.model.RichContact

class MobileContactsControllerTest extends Specification {

  "MobileContactsController" should {
    "serialize a contact with a name" in {
      new MobileContactsController(null, null).serializeContact(
        RichContact(
          email = EmailAddress("jim@davis.name"),
          name = Some("James R. Davis"),
          firstName = Some("Jim"),
          lastName = Some("Davis")
        )
      ) === Json.arr("jim@davis.name", "James R. Davis")
    }

    "serialize a contact with no name" in {
      new MobileContactsController(null, null).serializeContact(
        RichContact(
          email = EmailAddress("bill@wattersons.org")
        )
      ) === Json.arr("bill@wattersons.org")
    }
  }

}

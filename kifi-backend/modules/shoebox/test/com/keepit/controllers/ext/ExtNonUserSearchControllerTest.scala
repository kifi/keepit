package com.keepit.controllers.ext

import com.keepit.common.mail.EmailAddress

import org.specs2.mutable.Specification

import play.api.libs.json.Json
import com.keepit.abook.RichContact

class ExtNonUserSearchControllerTest extends Specification {

  "ExtNonUserSearchController" should {
    "serialize a contact with a name" in {
      new ExtNonUserSearchController(null, null).serializeContact(
        RichContact(
          email = EmailAddress("jim@davis.name"),
          name = Some("James R. Davis"),
          firstName = Some("Jim"),
          lastName = Some("Davis")
        )
      ) === Json.obj("email" -> "jim@davis.name", "name" -> "James R. Davis")
    }

    "serialize a contact with no name" in {
      new ExtNonUserSearchController(null, null).serializeContact(
        RichContact(
          email = EmailAddress("bill@wattersons.org")
        )
      ) === Json.obj("email" -> "bill@wattersons.org")
    }
  }

}

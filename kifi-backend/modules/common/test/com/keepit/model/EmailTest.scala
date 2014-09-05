package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import org.specs2.mutable.Specification

class EmailTest extends Specification {

  "Email Tags" should {
    import Email.placeholders._

    val ld = Email.tagLeftDelim
    val rd = Email.tagRightDelim

    val id42 = Id[User](42)
    val id43 = Id[User](43)
    val id44 = Id[User](44)

    "title" in {
      Email.placeholders.title.toString() === ld + "[\"title\"]" + rd
    }
    "firstName" in {
      firstName(id42).toString() === ld + "[\"firstName\",42]" + rd
    }

    "lastName" in {
      lastName(id43).toString() === ld + "[\"lastName\",43]" + rd
    }

    "fullName" in {
      fullName(id44).toString() === ld + "[\"fullName\",44]" + rd
    }

    "userExternalId" in {
      userExternalId(id43).toString() === ld + "[\"userExternalId\",43]" + rd
    }

    "avatarUrl" in {
      avatarUrl(id44).toString() === ld + "[\"avatarUrl\",44]" + rd
    }

    "unsubscribeUrl" in {
      unsubscribeUrl.toString() === ld + "[\"unsubscribeUrl\"]" + rd
    }

    "unsubscribeUserUrl" in {
      unsubscribeUrl(id42).toString() === ld + "[\"unsubscribeUserUrl\",42]" + rd
    }

    "unsubscribeEmailUrl" in {
      unsubscribeUrl(EmailAddress("bob@kifi.com")).toString() === ld + "[\"unsubscribeEmailUrl\",\"bob@kifi.com\"]" + rd
    }

  }

}

package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.common.mail.template.Tag
import org.specs2.mutable.Specification

class EmailTest extends Specification {

  "Email Tags" should {
    import Tag.{ tagLeftDelim, tagRightDelim }
    import com.keepit.common.mail.template.helpers._
    import com.keepit.common.mail.template.helpers.{ title => htitle }

    def tag(str: String) = tagLeftDelim + str + tagRightDelim

    val id42 = Id[User](42)
    val id43 = Id[User](43)
    val id44 = Id[User](44)

    "title" in {
      htitle.body === tag("[\"title\"]")
    }

    "firstName" in {
      firstName(id42).body === tag("[\"firstName\",42]")
    }

    "lastName" in {
      lastName(id43).body === tag("[\"lastName\",43]")
    }

    "fullName" in {
      fullName(id44).body === tag("[\"fullName\",44]")
    }

    "userExternalId" in {
      userExternalId(id43).body === tag("[\"userExternalId\",43]")
    }

    "avatarUrl" in {
      avatarUrl(id44).body === tag("[\"avatarUrl\",44]")
    }

    "unsubscribeUrl" in {
      unsubscribeUrl.body === tag("[\"unsubscribeUrl\"]")
    }

    "unsubscribeUserUrl" in {
      unsubscribeUrl(id42).body === tag("[\"unsubscribeUserUrl\",42]")
    }

    "unsubscribeEmailUrl" in {
      unsubscribeUrl(EmailAddress("bob@kifi.com")).body === tag("[\"unsubscribeEmailUrl\",\"bob@kifi.com\"]")
    }

  }

}

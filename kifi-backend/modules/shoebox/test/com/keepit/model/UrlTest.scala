package com.keepit.model

import org.specs2.mutable._
import com.keepit.common.db.Id
import com.keepit.common.db.slick._
import com.keepit.test.ShoeboxTestInjector

class UrlTest extends Specification with ShoeboxTestInjector {

  "Url" should {
    "correctly parse domains when using the factory" in {
      val uri = URLFactory("https://mail.google.com/mail/u/1/", Id[NormalizedURI](42))
      uri.domain === Some("mail.google.com")
    }
  }
}

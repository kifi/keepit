package com.keepit.common.mail

import com.keepit.common.mail.template.EmailToSend
import com.keepit.model.NotificationCategory
import com.keepit.test._
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.twirl.api.Html

class MailHelpersTest extends Specification with ShoeboxTestInjector {

  "MailHelpers" should {
    "format tags" in {
      val baseString = "this is a [#test] tag!"
      val res = MailHelpers.textWithTags(baseString).body
      res === """<span title="">this is a </span><a href="https://www.kifi.com/find?q=tag:%22test%22" title="">#test</a><span title=""> tag!</span>"""
    }
  }
}

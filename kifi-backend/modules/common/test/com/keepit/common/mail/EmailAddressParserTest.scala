package com.keepit.common.mail

import com.keepit.common.net.Host
import org.specs2.mutable.Specification

class EmailAddressParserTest extends Specification {

  "EmailAddressParser" should {
    "fail to parse a string that is not an email address" in {
      EmailAddressParser.parseOpt("a") === None
      EmailAddressParser.parseOpt("@") === None
      EmailAddressParser.parseOpt("@a") === None
      EmailAddressParser.parseOpt("a@") === None
    }
    "parse an email address" in {
      EmailAddressParser.parseOpt("a@b") === Some(ParsedEmailAddress(LocalPart(None, "a", Nil, None), Host("b")))
    }
    "parse an email address with tags" in {
      EmailAddressParser.parseOpt("a+b+c@d") === Some(ParsedEmailAddress(LocalPart(None, "a", Seq(Tag("b"), Tag("c")), None), Host("d")))
    }
    "handle (simple) quoted string" in {
      EmailAddressParser.parseOpt("\"abc\"@d") === Some(ParsedEmailAddress(LocalPart(None, "abc", Nil, None), Host("d")))
      EmailAddressParser.parseOpt("\"abc_def\"@d") === Some(ParsedEmailAddress(LocalPart(None, "abc_def", Nil, None), Host("d")))
      EmailAddressParser.parseOpt("\"abc def\"@d") === None // non-compliant but oh well
    }
    "reject address with unbalanced quotes" in {
      EmailAddressParser.parseOpt("abc\"@d") === None
      EmailAddressParser.parseOpt("abc\"de@f") === None
      EmailAddressParser.parseOpt("\"abc_def\"ghi@d") === None
    }
  }

}

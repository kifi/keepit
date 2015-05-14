package com.keepit.common.mail

import org.specs2.mutable.Specification

import play.api.libs.json.{ JsString, JsSuccess, JsError }

class EmailAddressTest extends Specification {

  def human(address: String) = EmailAddress.isLikelyHuman(EmailAddress(address))

  "EmailAddress" should {
    "accept valid email addresses without altering them" in {
      EmailAddress("a@b").address === "a@b"
      EmailAddress("a+b+c@d.com").address === "a+b+c@d.com"
      EmailAddress("jmarlow+@cs.cmu.edu").address === "jmarlow+@cs.cmu.edu"
      EmailAddress("TRACE@noreply.GiThUb.com").address === "TRACE@noreply.GiThUb.com"
      EmailAddress("jobs-unsubscribe@perl.org").address === "jobs-unsubscribe@perl.org"
      EmailAddress("arthur.droid@student.ecp.fr").address === "arthur.droid@student.ecp.fr"
      EmailAddress("chris.o'donnell@hollywood.com").address === "chris.o'donnell@hollywood.com"
      EmailAddress("Michelle.HernandezRosa@AMC.COM").address === "Michelle.HernandezRosa@AMC.COM"
      EmailAddress("ninja_turtle_babe2126@hotmail.com").address === "ninja_turtle_babe2126@hotmail.com"
    }
    "reject invalid email addresses" in {
      EmailAddress.validate("a") must beFailedTry.withThrowable[IllegalArgumentException]
      EmailAddress.validate("@") must beFailedTry.withThrowable[IllegalArgumentException]
      EmailAddress.validate("@a") must beFailedTry.withThrowable[IllegalArgumentException]
      EmailAddress.validate("a@") must beFailedTry.withThrowable[IllegalArgumentException]
      EmailAddress.validate("\"a\"@b") must beFailedTry.withThrowable[IllegalArgumentException]
    }
    "canonicalize (lowercase) domains when reading from JSON" in {
      readFromJson("a@b") === JsSuccess(EmailAddress("a@b"))
      readFromJson("Michelle.HernandezRosa@AMC.COM") === JsSuccess(EmailAddress("Michelle.HernandezRosa@amc.com"))
    }
    "reject invalid email addresses when reading from JSON" in {
      readFromJson("a") must haveClass[JsError]
      readFromJson("@") must haveClass[JsError]
      readFromJson("a@") must haveClass[JsError]
      readFromJson("@a") must haveClass[JsError]
      readFromJson("@@") must haveClass[JsError]
    }
    "canonicalize (lowercase) domains when binding from query parameter" in {
      bindFromQueryParameter("a@b") === Some(Right(EmailAddress("a@b")))
      bindFromQueryParameter("Michelle.HernandezRosa@AMC.COM") === Some(Right(EmailAddress("Michelle.HernandezRosa@amc.com")))
    }
    "reject invalid email addresses when binding from query parameter" in {
      bindFromQueryParameter("a") === Some(Left("Invalid email address: a"))
      bindFromQueryParameter("@") === Some(Left("Invalid email address: @"))
      bindFromQueryParameter("a@") === Some(Left("Invalid email address: a@"))
      bindFromQueryParameter("@a") === Some(Left("Invalid email address: @a"))
      bindFromQueryParameter("@@") === Some(Left("Invalid email address: @@"))
    }
    "have case-sensitive equality" in {
      EmailAddress("a@b") == EmailAddress("a@b") === true
      EmailAddress("A@b") == EmailAddress("a@b") === false
    }
    "support case-insensitive equality checks" in {
      EmailAddress("a@b").equalsIgnoreCase(EmailAddress("a@b")) === true
      EmailAddress("a@b").equalsIgnoreCase(EmailAddress("A@b")) === true
      EmailAddress("a@b").equalsIgnoreCase(EmailAddress("a@c")) === false
    }
    "support case-insensitive comparisons" in {
      EmailAddress("a@b").compareToIgnoreCase(EmailAddress("a@b")) === 0
      EmailAddress("a@b").compareToIgnoreCase(EmailAddress("A@b")) === 0
      EmailAddress("a@b").compareToIgnoreCase(EmailAddress("a@c")) must be_<(0)
      EmailAddress("a@c").compareToIgnoreCase(EmailAddress("a@b")) must be_>(0)
    }

    "identify likely human email addresses" in {
      human("jared@kifi.com") === true
      human("abra@stanford.edu") === true
      human("jmarlow+@cs.cmu.edu") === true
      human("arthur.droid@student.ecp.fr") === true
      human("chris.o'donnell@hollywood.com") === true
      human("Michelle.HernandezRosa@amc.com") === true
      human("ninja_turtle_babe2126@hotmail.com") === true
    }

    "identify likely non-human email addresses" in {
      human("unsubscribe@handl.it") === false
      human("no-reply@wordpress.com") === false
      human("TRACE@noreply.github.com") === false
      human("jobs-unsubscribe@perl.org") === false
      human("post+4c7841a9bf168@ohlife.com") === false
      human("messages-noreply@linkedin.com") === false
      human("x+7368498674275@mail.asana.com") === false
      human("dev-subscribe@subversion.tigris.org") === false
      human("student+unsubscribe@cs.stanford.edu") === false
      human("unsubscribe@enews.rosewoodhotels.com") === false
      human("ks3bp-3580038719@sale.craigslist.org") === false
      human("support+id3476888@ubercab.zendesk.com") === false
      human("pig-dev-unsubscribe@hadoop.apache.org") === false
      human("bloggerdev-unsubscribe@yahoogroups.com") === false
      human("comp.lang.php-unsubscribe@googlegroups.com") === false
      human("thrift-user-subscribe@incubator.apache.org") === false
      human("2c513ox7dnruuaeamshk71vj5i62@reply.airbnb.com") === false
      human("r+0gkz6vfwb0F-hHX9aRoQxNS6_MxZaA0YWig@indeedmail.com") === false
      human("m+82egqjq000000975o32002e4heuvwn41uj@reply.facebook.com") === false
      human("msg-reply-8c2c4e9015b5d06c7ffa03608be226b5@reply.angel.co") === false
      human("Nathaniel+Amarose-04791618235241941441-Bj9zsj96@prod.writely.com") === false
      human("8c29fb17e6e5823f5e760b60ca109ce5+8163495-8989571@inbound.postmarkapp.com") === false
      human("reply+i-7787194-41b5e3e2217dd430b85fa5a942e84e30772877d7-1576151@reply.github.com") === false
    }
  }

  private def readFromJson(addr: String) = EmailAddress.format.reads(JsString(addr))
  private def bindFromQueryParameter(addr: String) = EmailAddress.queryStringBinder.bind("e", Map("e" -> Seq(addr)))
}

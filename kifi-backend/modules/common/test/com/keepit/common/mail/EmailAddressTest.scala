package com.keepit.common.mail

import org.specs2.mutable.Specification

import play.api.libs.json.{ JsString, JsSuccess, JsError }
import play.api.mvc.QueryStringBindable

class EmailAddressTest extends Specification {

  "EmailAddress" should {
    "accept valid email addresses without altering them (except for lowercasing domains)" in {
      EmailAddress.validate("a@b").get.address === "a@b"
      EmailAddress.validate("a+b+c@d.com").get.address === "a+b+c@d.com"
      EmailAddress.validate("jmarlow+@cs.cmu.edu").get.address === "jmarlow+@cs.cmu.edu"
      EmailAddress.validate("TRACE@noreply.GiThUb.com").get.address === "TRACE@noreply.github.com"
      EmailAddress.validate("jobs-unsubscribe@perl.org").get.address === "jobs-unsubscribe@perl.org"
      EmailAddress.validate("arthur.droid@student.ecp.fr").get.address === "arthur.droid@student.ecp.fr"
      EmailAddress.validate("chris.o'donnell@hollywood.com").get.address === "chris.o'donnell@hollywood.com"
      EmailAddress.validate("Michelle.HernandezRosa@AMC.COM").get.address === "Michelle.HernandezRosa@amc.com"
      EmailAddress.validate("ninja_turtle_babe2126@hotmail.com").get.address === "ninja_turtle_babe2126@hotmail.com"
    }
    "reject invalid email addresses" in {
      EmailAddress.validate("a").get must throwAn[IllegalArgumentException]
      EmailAddress.validate("@").get must throwAn[IllegalArgumentException]
      EmailAddress.validate("@a").get must throwAn[IllegalArgumentException]
      EmailAddress.validate("a@").get must throwAn[IllegalArgumentException]
      EmailAddress.validate("\"a\"@b").get must throwAn[IllegalArgumentException]
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
  }

  private def readFromJson(addr: String) = EmailAddress.format.reads(JsString(addr))
  private def bindFromQueryParameter(addr: String) = EmailAddress.queryStringBinder(QueryStringBindable.bindableString).bind("e", Map("e" -> Seq(addr)))
}

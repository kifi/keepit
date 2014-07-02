package com.keepit.common.mail

import org.specs2.mutable.Specification

import play.api.libs.json.{JsString, JsSuccess, JsError}
import play.api.mvc.QueryStringBindable

class EmailAddressTest extends Specification {

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
      EmailAddress("a") must throwAn[IllegalArgumentException]
      EmailAddress("@") must throwAn[IllegalArgumentException]
      EmailAddress("@a") must throwAn[IllegalArgumentException]
      EmailAddress("a@") must throwAn[IllegalArgumentException]
      EmailAddress("\"a\"@b") must throwAn[IllegalArgumentException]
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

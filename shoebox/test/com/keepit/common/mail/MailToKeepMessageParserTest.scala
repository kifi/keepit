package com.keepit.common.mail

import java.util.Properties

import org.specs2.mutable.Specification

import com.keepit.common.db.slick.Database
import com.keepit.inject.inject
import com.keepit.model.{EmailAddress, User, UserRepo, EmailAddressRepo}

import javax.mail.Message.RecipientType
import javax.mail.Session
import javax.mail.internet.{MimeMultipart, MimeBodyPart, InternetAddress, MimeMessage}
import play.api.Play.current
import play.api.test.Helpers.running
import com.keepit.test.EmptyApplication

class MailToKeepMessageParserTest extends Specification {
  "MailToKeepMessageParser" should {
    "parse out the text from multipart emails" in {
      running(new EmptyApplication()) {
        val parser = new MailToKeepMessageParser(inject[Database], inject[EmailAddressRepo], inject[UserRepo])

        val session = Session.getDefaultInstance(new Properties())
        val message = new MimeMessage(session)
        message.setSubject("hi")
        message.setFrom(new InternetAddress("eishay@42go.com"))
        message.setRecipient(RecipientType.TO, new InternetAddress("greg@42go.com"))

        val content = new MimeMultipart("alternative")
        val (text, html) = (new MimeBodyPart(), new MimeBodyPart())
        text.setText("Hey, this is plain text (http://google.com)")
        val htmlText = "<p>Hey, this is html (yahoo.com)</p>"
        html.setContent(htmlText, "text/html")
        content.addBodyPart(html)
        content.addBodyPart(text)
        message.setContent(content)
        message.saveChanges()

        parser.getText(message) must beSome("Hey, this is plain text (http://google.com)")
        parser.getUris(message).map(_.toString) === Seq("http://google.com")
      }
    }
    "parse out uris correctly from HTML" in {
      running(new EmptyApplication()) {
        val parser = new MailToKeepMessageParser(inject[Database], inject[EmailAddressRepo], inject[UserRepo])

        val session = Session.getDefaultInstance(new Properties())
        val message = new MimeMessage(session)
        message.setSubject("Hey, this is Eishay from 42go.com")
        message.setFrom(new InternetAddress("eishay@42go.com"))
        message.setRecipient(RecipientType.TO, new InternetAddress("greg@methvin.net"))

        val htmlText = "<p>Hey, you should check out google.com/search and HTTP://YAHOO.COM/</p> " +
          "<a href=3D\"mailto:effi@fuks.co.il\" target=\"_blank\">effi@fuks.co.il</a>"
        message.setContent(htmlText, "text/html")
        message.saveChanges()

        parser.getUris(message).map(_.toString.toLowerCase) ===
            Seq("http://42go.com", "http://google.com/search", "http://yahoo.com")
      }
    }
    "parse out users correctly" in {
      running(new EmptyApplication()) {
        val db = inject[Database]
        val emailAddressRepo = inject[EmailAddressRepo]
        val userRepo = inject[UserRepo]
        val (eishay, greg) = db.readWrite { implicit s =>
          (userRepo.save(User(firstName = "Eishay", lastName = "Smith")),
              userRepo.save(User(firstName = "Greg", lastName = "Methvin")))
        }
        db.readWrite { implicit s =>
          emailAddressRepo.save(EmailAddress(address = "eishay@42go.com", userId = eishay.id.get))
          emailAddressRepo.save(EmailAddress(address = "greg@42go.com", userId = greg.id.get))
        }
        val parser = new MailToKeepMessageParser(db, emailAddressRepo, userRepo)
        val session = Session.getDefaultInstance(new Properties())
        val message = new MimeMessage(session)
        message.setSubject("hi")
        message.setFrom(new InternetAddress("eishay@42go.com"))
        message.setRecipient(RecipientType.TO, new InternetAddress("greg@42go.com"))
        message.setContent("Hey, you should check out http://google.com/.", "text/html")
        message.saveChanges()

        parser.getUser(message).get.firstName === "Eishay"
      }
    }
  }
}

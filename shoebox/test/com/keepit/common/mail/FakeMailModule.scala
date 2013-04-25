package com.keepit.common.mail

import scala.collection.mutable.MutableList

import com.google.inject._
import com.keepit.common.db.slick.DBSession.RWSession
import com.tzavellas.sse.guice.ScalaModule

class FakeOutbox(val mails: MutableList[ElectronicMail] = MutableList()) {
  def add(email: ElectronicMail): ElectronicMail = {
    mails += email
    email
  }
  def size = mails.size
  def head = mails.head
  def apply(i: Int) = mails(i)
}

class FakeMailProvider(emails: FakeOutbox) extends MailProvider {
  def sendMail(mail: ElectronicMail): ElectronicMail = {
    emails.add(mail)
    mail
  }
}

case class FakeMailModule() extends ScalaModule {

  override def configure(): Unit = {
    bind[FakeOutbox].toInstance(new FakeOutbox())
  }

  @Provides
  def postOfficeProvider(emails: FakeOutbox): PostOffice = {
    new PostOffice() {
      def sendMail(mail: ElectronicMail)(implicit s: RWSession): ElectronicMail = {
        val sent = mail.prepareToSend().sent("fake sent", ElectronicMailMessageId("475082848.3.1353745094337.JavaMail.eishay@eishay-mbp.local"))
        emails.add(sent)
        sent
      }
    }
  }

  @Provides
  def fakeMailProvider(emails: FakeOutbox): MailProvider = new FakeMailProvider(emails)
}

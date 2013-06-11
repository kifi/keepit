package com.keepit.common.mail

import scala.collection.mutable.MutableList

import net.codingwell.scalaguice.ScalaModule

import com.google.inject._
import com.keepit.common.db.slick.DBSession.RWSession

class FakeOutbox(val mails: MutableList[ElectronicMail] = MutableList()) {
  def add(email: ElectronicMail): Unit = mails += email
  def size = mails.size
  def head = mails.head
  def apply(i: Int) = mails(i)
}

class FakeMailProvider(emails: FakeOutbox) extends MailProvider {
  def sendMail(mail: ElectronicMail) = emails.add(mail)
}

case class FakeMailModule() extends ScalaModule {

  override def configure(): Unit = {
    bind[FakeOutbox].toInstance(new FakeOutbox())
  }

  @Provides
  def postOfficeProvider(mailRepo: ElectronicMailRepo, emails: FakeOutbox): LocalPostOffice = {
    new LocalPostOffice() {
      def sendMail(mail: ElectronicMail)(implicit s: RWSession): ElectronicMail = {
        val saved = mailRepo.save(mail)
        saved.prepareToSend().sent("fake sent", ElectronicMailMessageId("475082848.3.1353745094337.JavaMail.eishay@eishay-mbp.local"))
        emails.add(saved)
        saved
      }
    }
  }

  @Provides
  def fakeMailProvider(emails: FakeOutbox): MailProvider = new FakeMailProvider(emails)
}

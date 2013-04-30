package com.keepit.common.mail

import scala.collection.mutable.MutableList
import com.keepit.common.db._

import com.google.inject._
import com.keepit.common.db.slick.DBSession.RWSession
import com.tzavellas.sse.guice.ScalaModule

class FakeOutbox(val mails: MutableList[Id[ElectronicMail]] = MutableList()) {
  def add(email: Id[ElectronicMail]): Unit = mails += email
  def size = mails.size
  def head = mails.head
  def apply(i: Int) = mails(i)
}

class FakeMailProvider(emails: FakeOutbox) extends MailProvider {
  def sendMail(mailId: Id[ElectronicMail]) = emails.add(mailId)
}

case class FakeMailModule() extends ScalaModule {

  override def configure(): Unit = {
    bind[FakeOutbox].toInstance(new FakeOutbox())
  }

  @Provides
  def postOfficeProvider(mailRepo: ElectronicMailRepo, emails: FakeOutbox): PostOffice = {
    new PostOffice() {
      def sendMail(mail: ElectronicMail)(implicit s: RWSession): ElectronicMail = {
        val saved = mailRepo.save(mail)
        saved.prepareToSend().sent("fake sent", ElectronicMailMessageId("475082848.3.1353745094337.JavaMail.eishay@eishay-mbp.local"))
        emails.add(saved.id.get)
        saved
      }
    }
  }

  @Provides
  def fakeMailProvider(emails: FakeOutbox): MailProvider = new FakeMailProvider(emails)
}

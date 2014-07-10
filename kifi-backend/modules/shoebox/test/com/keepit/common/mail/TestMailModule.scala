package com.keepit.common.mail

import scala.collection.mutable.MutableList

import net.codingwell.scalaguice.ScalaModule

import com.google.inject._
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.healthcheck.{ AirbrakeNotifier, LocalSystemAdminMailSender, SystemAdminMailSender }
import com.amazonaws.auth.BasicAWSCredentials
import com.keepit.common.logging.Logging

case class TestMailModule() extends MailModule {
  def configure() {}

  @Singleton
  @Provides
  override def optoutSecret: OptoutSecret = OptoutSecret("""&some1sec\re#t2str;in''g3that4we5use6for7te%sting""")

  @Provides
  @Singleton
  def mailSenderPlugin: MailSenderPlugin = new MailSenderPlugin {
    def processMail(mailId: ElectronicMail) = throw new Exception("Should not attempt to use mail plugin in test")
    def processOutbox() = throw new Exception("Should not attempt to use mail plugin in test")
  }

  @Provides
  @Singleton
  def localPostOffice(shoeboxPostOfficeImpl: ShoeboxPostOfficeImpl): LocalPostOffice = shoeboxPostOfficeImpl

  @Provides
  @Singleton
  def fakeSystemAdminMailSender(): SystemAdminMailSender = new FakeSystemAdminMailSender()
}

class FakeSystemAdminMailSender extends SystemAdminMailSender {
  def sendMail(email: ElectronicMail): Unit = println(email.toString)
}

class FakeOutbox(val mails: MutableList[ElectronicMail] = MutableList()) {
  def add(email: ElectronicMail): Unit = mails += email
  def size = mails.size
  def head = mails.head
  def apply(i: Int) = mails(i)
  def all = mails.toVector
}

class FakeMailProvider(emails: FakeOutbox) extends MailProvider {
  def sendMail(mail: ElectronicMail) = emails.add(mail)
}

case class FakeMailModule() extends MailModule {

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

package com.keepit.common.mail

import com.tzavellas.sse.guice.ScalaModule
import com.google.inject._
import com.google.inject.binder._
import akka.actor.Actor._
import akka.actor._
import akka.dispatch.Await
import akka.pattern.ask
import akka.util.duration._
import play.api.Play.current
import play.api.Configuration
import scala.collection.mutable.MutableList

class FakeOutbox(var mails: MutableList[ElectronicMail] = MutableList()) {
  def add(email: ElectronicMail): ElectronicMail = {
    mails += email
    email
  }
  def size = mails.size
  def apply(i: Int) = mails(i)
}

case class FakeMailModule() extends ScalaModule {

  override def configure(): Unit = {
    bind[FakeOutbox].toInstance(new FakeOutbox())
  }

  @Provides
  def postOfficeProvider(emails: FakeOutbox): PostOffice = {
    new PostOffice() {
      def sendMail(mail: ElectronicMail): ElectronicMail = emails.add(mail)
    }
  }
}

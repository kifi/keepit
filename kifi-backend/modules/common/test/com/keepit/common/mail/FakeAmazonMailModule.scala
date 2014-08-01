package com.keepit.common.mail

import com.google.inject.{ Provides, Singleton }

case class FakeAmazonMailModule() extends AmazonMailModule {

  def configure() {
  }

  @Singleton
  @Provides
  def amazonSimpleMailProvider(): AmazonSimpleMailProvider =
    new AmazonSimpleMailProvider() {
      def sendMail(mail: ElectronicMail): Unit = println(mail)
    }

}

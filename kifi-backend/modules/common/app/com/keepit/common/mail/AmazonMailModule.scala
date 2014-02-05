package com.keepit.common.mail

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import com.amazonaws.auth.BasicAWSCredentials
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClient
import com.keepit.common.aws.AwsModule

trait AmazonMailModule extends ScalaModule {
}

class ProdAmazonMailModule extends AmazonMailModule {
  def configure() {
  }

  @Singleton
  @Provides
  def amazonSimpleMailProvider(basicAWSCredentials: BasicAWSCredentials, airbrake: AirbrakeNotifier): AmazonSimpleMailProvider =
    new AmazonSimpleMailProviderImpl(new AmazonSimpleEmailServiceClient(basicAWSCredentials), airbrake)

}

class DevAmazonMailModule extends AmazonMailModule {

  def configure() {
  }

  @Singleton
  @Provides
  def amazonSimpleMailProvider(): AmazonSimpleMailProvider =
    new AmazonSimpleMailProvider(){
      def sendMail(mail: ElectronicMail): Unit = println(mail)
    }

}

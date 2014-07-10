package com.keepit.common.mail

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }
import com.amazonaws.auth.BasicAWSCredentials
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClient
import com.keepit.common.aws.AwsModule
import com.amazonaws.regions._
import com.amazonaws.AmazonWebServiceClient

trait AmazonMailModule extends ScalaModule {
}

class ProdAmazonMailModule extends AmazonMailModule {
  def configure() {
  }

  @Singleton
  @Provides
  def amazonSimpleMailProvider(basicAWSCredentials: BasicAWSCredentials): AmazonSimpleMailProvider = {
    //using the blocking client as we want to manage our own threading
    val client = new AmazonSimpleEmailServiceClient(basicAWSCredentials)
    client.asInstanceOf[AmazonWebServiceClient].setRegion(Region.getRegion(Regions.US_EAST_1))
    new AmazonSimpleMailProviderImpl(client)
  }

}

class DevAmazonMailModule extends AmazonMailModule {

  def configure() {
  }

  @Singleton
  @Provides
  def amazonSimpleMailProvider(): AmazonSimpleMailProvider =
    new AmazonSimpleMailProvider() {
      def sendMail(mail: ElectronicMail): Unit = println(mail)
    }

}

package com.keepit.common.aws

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }
import com.amazonaws.auth.BasicAWSCredentials
import play.api.Play._
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.regions._
import com.keepit.inject.AppScoped
import play.api.Play

case class AwsConfig(sqsEnabled: Boolean)

case class AwsModule() extends ScalaModule {
  def configure: Unit = {
    bind[FortyTwoElasticLoadBalancingClient].to[FortyTwoElasticLoadBalancingClientImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def awsConfig: AwsConfig =
    AwsConfig(Play.maybeApplication.isDefined && Play.current.configuration.getBoolean("amazon.sqs.enable").getOrElse(false))

  @Singleton
  @Provides
  def basicAWSCredentials: BasicAWSCredentials = {
    val conf = current.configuration.getConfig("amazon").get
    new BasicAWSCredentials(
      conf.getString("accessKey").get,
      conf.getString("secretKey").get)
  }

  @Singleton
  @Provides
  def amazonELBClient(basicAWSCredentials: BasicAWSCredentials): AmazonElasticLoadBalancingClient = {
    val client = new AmazonElasticLoadBalancingClient(basicAWSCredentials)
    val region: Regions = Regions.valueOf(current.configuration.getString("amazon.region").get)
    client.setRegion(Region.getRegion(region))
    client
  }
}

case class AwsDevModule() extends ScalaModule {
  def configure: Unit = {
    bind[FortyTwoElasticLoadBalancingClient].to[FortyTwoElasticLoadBalancingClientImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def awsConfig: AwsConfig = AwsConfig(sqsEnabled = false)

  @Singleton
  @Provides
  def basicAWSCredentials: BasicAWSCredentials = new BasicAWSCredentials("accessKey", "secretKey")

  @Singleton
  @Provides
  def amazonELBClient(basicAWSCredentials: BasicAWSCredentials): AmazonElasticLoadBalancingClient = {
    new AmazonElasticLoadBalancingClient(basicAWSCredentials)
  }
}

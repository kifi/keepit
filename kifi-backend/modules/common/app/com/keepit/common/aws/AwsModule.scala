package com.keepit.common.aws

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import com.amazonaws.auth.BasicAWSCredentials
import play.api.Play._
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient

class AwsModule extends ScalaModule {
  def configure: Unit = { }

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
    new AmazonElasticLoadBalancingClient(basicAWSCredentials)
  }
}

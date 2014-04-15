package com.keepit.graph

import com.keepit.inject.CommonProdModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.common.service.ServiceType
import com.keepit.common.amazon.AmazonInstanceInfo

import com.google.inject.{Provides, Singleton}

import com.kifi.franz.{SimpleSQSClient, QueueName, SQSQueue}

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions._


case class GraphProdModule() extends GraphModule with CommonProdModule {
  val discoveryModule = new ProdDiscoveryModule {
    def servicesToListenOn = ServiceType.SHOEBOX :: ServiceType.ELIZA :: ServiceType.ABOOK :: Nil
  }

  @Singleton
  @Provides
  def graphInboxQueue(basicAWSCreds:BasicAWSCredentials, amazonInstanceInfo: AmazonInstanceInfo): SQSQueue[ReplaceMeLéo] = {
    val client = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered=false)
    client.formatted[ReplaceMeLéo](QueueName("graph-inbox-prod-b-" + amazonInstanceInfo.instanceId.id), true)
  }
}

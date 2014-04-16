package com.keepit.graph

import com.keepit.inject.CommonProdModule
import com.keepit.common.zookeeper.ProdDiscoveryModule
import com.keepit.common.service.ServiceType
import com.keepit.common.amazon.AmazonInstanceInfo

import com.google.inject.{Provides, Singleton}

import com.kifi.franz.{SimpleSQSClient, QueueName, SQSQueue}

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions._
import com.keepit.graph.manager.GraphUpdate


case class GraphProdModule() extends GraphModule with CommonProdModule {
  val discoveryModule = new ProdDiscoveryModule {
    def servicesToListenOn = ServiceType.SHOEBOX :: ServiceType.ELIZA :: ServiceType.ABOOK :: Nil
  }

  @Provides @Singleton
  def graphInboxQueue(basicAWSCreds:BasicAWSCredentials, amazonInstanceInfo: AmazonInstanceInfo): SQSQueue[GraphUpdate] = {
    val client = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered=false)
    client.formatted[GraphUpdate](QueueName("graph-inbox-prod-b-" + amazonInstanceInfo.instanceId.id), true)
  }
}

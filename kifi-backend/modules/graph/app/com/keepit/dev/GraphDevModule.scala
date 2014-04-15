package com.keepit.dev

import com.keepit.inject.CommonDevModule
import com.keepit.graph.{GraphModule}
import com.keepit.common.amazon.AmazonInstanceInfo

import com.google.inject.{Provides, Singleton}

import com.kifi.franz.{SimpleSQSClient, QueueName, SQSQueue}

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions._
import com.keepit.graph.ingestion.GraphUpdate

case class GraphDevModule() extends GraphModule with CommonDevModule {


  @Provides @Singleton
  def graphInboxQueue(basicAWSCreds:BasicAWSCredentials, amazonInstanceInfo: AmazonInstanceInfo): SQSQueue[GraphUpdate] = {
    val client = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered=false)
    client.formatted[GraphUpdate](QueueName("graph-inbox-dev-" + amazonInstanceInfo.instanceId.id), true)
  }

}

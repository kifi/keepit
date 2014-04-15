package com.keepit.dev

import com.keepit.inject.CommonDevModule
import com.keepit.graph.{GraphModule, ReplaceMeLéo}
import com.keepit.common.amazon.AmazonInstanceInfo

import com.google.inject.{Provides, Singleton}

import com.kifi.franz.{SimpleSQSClient, QueueName, SQSQueue}

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions._

case class GraphDevModule() extends GraphModule with CommonDevModule {


  @Singleton
  @Provides
  def graphInboxQueue(basicAWSCreds:BasicAWSCredentials, amazonInstanceInfo: AmazonInstanceInfo): SQSQueue[ReplaceMeLéo] = {
    val client = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered=false)
    client.formatted[ReplaceMeLéo](QueueName("graph-inbox-dev-" + amazonInstanceInfo.instanceId.id), true)
  }

}

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
import com.keepit.common.store.GraphProdStoreModule
import com.keepit.graph.simple.SimpleGraphProdModule


case class GraphProdModule() extends GraphModule(GraphProdStoreModule(), SimpleGraphProdModule()) with CommonProdModule {
  val discoveryModule = new ProdDiscoveryModule {
    def servicesToListenOn = ServiceType.SHOEBOX :: ServiceType.ELIZA :: ServiceType.ABOOK :: Nil
  }
}

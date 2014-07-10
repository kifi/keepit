package com.keepit.common.aws

import com.google.inject.Inject
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.keepit.common.amazon.AmazonInstanceInfo
import com.amazonaws.services.elasticloadbalancing.model.{ RegisterInstancesWithLoadBalancerRequest, InstanceState, Instance, DescribeInstanceHealthRequest }
import scala.collection.JavaConversions._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.common.logging.Logging

trait FortyTwoElasticLoadBalancingClient {
  def registerInstance(instance: AmazonInstanceInfo): Unit
  def getInstanceState(instance: AmazonInstanceInfo): Option[InstanceState]
}

class FortyTwoElasticLoadBalancingClientImpl @Inject() (
  loadBalancingClient: AmazonElasticLoadBalancingClient,
  airbrake: AirbrakeNotifier)
    extends FortyTwoElasticLoadBalancingClient with Logging {

  def registerInstance(instance: AmazonInstanceInfo): Unit = {
    val instanceId = instance.instanceId
    instance.loadBalancer map { loadBalancer =>
      val request = new RegisterInstancesWithLoadBalancerRequest(loadBalancer, Seq(new Instance(instanceId.id)))
      try {
        loadBalancingClient.registerInstancesWithLoadBalancer(request)
        log.info(s"[${currentDateTime.toStandardTimeString}] Registered instance $instanceId with load balancer $loadBalancer")
      } catch {
        case t: Throwable => airbrake.panic(s"[${currentDateTime.toStandardTimeString}] Error registering instance $instanceId with load balancer $loadBalancer: $t")
      }
    } getOrElse log.info(s"[${currentDateTime.toStandardTimeString}] No load balancer registered for instance $instanceId")
  }

  override def getInstanceState(instance: AmazonInstanceInfo): Option[InstanceState] = {
    val instanceId = instance.instanceId
    instance.loadBalancer flatMap { loadBalancer =>
      val request = new DescribeInstanceHealthRequest(loadBalancer).withInstances(Seq(new Instance(instanceId.id)))
      try {
        val state = loadBalancingClient.describeInstanceHealth(request).getInstanceStates.head
        log.info(s"Instance $instanceId in state ${state.getState} for load balancer $loadBalancer")
        Some(state)
      } catch {
        case t: Throwable => {
          airbrake.notify(s"Failed to check status of instance ${instanceId} with load balancer $loadBalancer: $t")
          None
        }
      }
    }
  }
}


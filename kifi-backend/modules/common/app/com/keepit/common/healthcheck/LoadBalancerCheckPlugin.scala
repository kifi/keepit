package com.keepit.common.healthcheck

import com.keepit.common.plugin.{SchedulingProperties, SchedulerPlugin}
import com.google.inject.Inject
import com.keepit.common.akka.{UnsupportedActorMessage, FortyTwoActor}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.logging.Logging
import scala.concurrent.duration._
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.keepit.common.zookeeper.ServiceDiscovery
import com.amazonaws.services.elasticloadbalancing.model.{DescribeInstanceHealthRequest, Instance}
import scala.collection.JavaConversions._

case object CheckLoadBalancer

class LoadBalancerCheckActor @Inject() (
  airbrake: AirbrakeNotifier,
  loadBalancingClient: AmazonElasticLoadBalancingClient,
  serviceDiscovery: ServiceDiscovery)
  extends FortyTwoActor(airbrake) {

  def checkLoadBalancer(): Unit = {
    for {
      instance <- serviceDiscovery.thisInstance
      loadBalancer <- instance.instanceInfo.loadBalancer
    } yield {
      if (instance.isAvailable) {
        val instanceId = instance.instanceInfo.instanceId
        val request = new DescribeInstanceHealthRequest(loadBalancer).withInstances(Seq(new Instance(instanceId.id)))
        try {
          val state = loadBalancingClient.describeInstanceHealth(request).getInstanceStates.head // throw an exception if empty result
          if (state.getState != "InService") {
            airbrake.notify(s"Instance ${instanceId} is considered ${state.getState} by load balancer $loadBalancer (reason: ${state.getReasonCode})")
          }
        } catch {
          case t:Throwable => airbrake.notify(s"Failed to check status of instance ${instanceId} with load balancer $loadBalancer")
        }
      }
    }
  }

  def receive() = {
    case CheckLoadBalancer => checkLoadBalancer()
    case m => throw new UnsupportedActorMessage(m)
  }
}

trait LoadBalancerCheckPlugin

class LoadBalancerCheckPluginImpl @Inject() (
  val scheduling: SchedulingProperties,
  actor: ActorInstance[LoadBalancerCheckActor])
  extends LoadBalancerCheckPlugin with SchedulerPlugin with Logging {

  override def enabled: Boolean = true
  override def onStart() {
    scheduleTaskOnAllMachines(actor.system, 0 seconds, 2 minutes, actor.ref, CheckLoadBalancer)
  }
}

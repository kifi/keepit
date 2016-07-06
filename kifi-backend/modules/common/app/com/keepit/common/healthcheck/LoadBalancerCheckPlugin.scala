package com.keepit.common.healthcheck

import com.keepit.common.plugin.{ SchedulingProperties, SchedulerPlugin }
import com.google.inject.Inject
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.logging.Logging
import scala.concurrent.duration._
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.aws.FortyTwoElasticLoadBalancingClient

case object CheckLoadBalancer

class LoadBalancerCheckActor @Inject() (
  airbrake: AirbrakeNotifier,
  loadBalancingClient: FortyTwoElasticLoadBalancingClient,
  serviceDiscovery: ServiceDiscovery)
    extends FortyTwoActor(airbrake) {

  def checkLoadBalancer(): Unit = {
    serviceDiscovery.thisInstance map { instance =>
      if (instance.isAvailable) {
        val info = instance.instanceInfo
        val stateOpt = loadBalancingClient.getInstanceState(info)
        stateOpt.filter(state => state.getState != "InService" && state.getReasonCode != "ELB") map { state =>
          airbrake.notify(s"Instance ${info.instanceId} is considered ${state.getState} by load balancer ${info.loadBalancer} (reason: ${state.getReasonCode})")
          loadBalancingClient.registerInstance(info)
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
  override def onStart() { //keep me alive!
    scheduleTaskOnAllMachines(actor.system, 0 seconds, 30 minutes, actor.ref, CheckLoadBalancer)
  }
}

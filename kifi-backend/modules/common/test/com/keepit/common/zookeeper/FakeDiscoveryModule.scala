package com.keepit.common.zookeeper

import com.keepit.common.service.ServiceType
import com.keepit.common.actor.FakeSchedulerModule

case class FakeDiscoveryModule() extends LocalDiscoveryModule(ServiceType.TEST_MODE) {

  def configure() {
    install(FakeSchedulerModule())
  }
}

package com.keepit.common.zookeeper

import com.keepit.common.service.ServiceType
import com.keepit.common.actor.FakeSchedulerModule

case class FakeDiscoveryModule() extends ProdDiscoveryModule(ServiceType.TEST_MODE) {

  override def configure() {
    install(FakeSchedulerModule())
  }
}

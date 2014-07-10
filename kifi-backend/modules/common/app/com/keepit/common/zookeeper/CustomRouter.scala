package com.keepit.common.zookeeper

trait CustomRouter {
  def update(routingList: Vector[ServiceInstance], refresh: () => Unit): Unit
}

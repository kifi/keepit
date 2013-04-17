package com.keepit.common.zookeeper

trait ZookeeperClientConfig {
  var hostList: String
  var sessionTimeout = 3000
  var basePath = ""

  def apply = {
    new ZooKeeperClient(this)
  }
}

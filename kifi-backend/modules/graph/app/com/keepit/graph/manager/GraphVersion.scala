package com.keepit.graph.manager

import com.keepit.common.zookeeper.ServiceDiscovery

case class GraphVersion(value: Int) {
  require(value >= 0)

  def dirSuffix = if (value == 0) "" else s"_v$value"
}

object GraphVersion {
  private val activeVersion = GraphVersion(1)
  private val backupVersion = GraphVersion(1)

  def getVersionByStatus(service: ServiceDiscovery): GraphVersion = if (service.hasBackupCapability) backupVersion else activeVersion
  def getVersionsForCleanup(): Seq[GraphVersion] = (0 until activeVersion.value).map { v => GraphVersion(v) }
}

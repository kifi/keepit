package com.keepit.search.index

import com.keepit.common.zookeeper.ServiceDiscovery

case class IndexerVersion(value: Int) {
  require(value >= 0)
  def indexNameSuffix: String = if (value == 0) "" else s"_v${value}"
}

object IndexerVersion {
  implicit def toInt(version: IndexerVersion): Int = version.value
  implicit def fromInt(value: Int) = IndexerVersion(value)
}

abstract class IndexerVersionProvider(activeVersion: IndexerVersion, backupVersion: IndexerVersion) {
  require(backupVersion >= activeVersion)
  def getVerionByStatus(service: ServiceDiscovery): IndexerVersion = if (service.isBackup) backupVersion else activeVersion
}

object IndexerVersionProviders {
  case object Article extends IndexerVersionProvider(0, 0)
  case object URIGraph extends IndexerVersionProvider(0, 0)
  case object Collection extends IndexerVersionProvider(0, 0)
  case object User extends IndexerVersionProvider(0, 0)
  case object UserGraph extends IndexerVersionProvider(0, 0)
  case object SearchFriend extends IndexerVersionProvider(0, 0)
  case object Message extends IndexerVersionProvider(0, 0)
  case object Phrase extends IndexerVersionProvider(0, 0)
  case object Spell extends IndexerVersionProvider(0, 0)
  case object Library extends IndexerVersionProvider(0, 0)
  case object Keep extends IndexerVersionProvider(0, 0)
}

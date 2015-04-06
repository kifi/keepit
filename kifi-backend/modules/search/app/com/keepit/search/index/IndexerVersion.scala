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

sealed abstract class IndexerVersionProvider(activeVersion: IndexerVersion, backupVersion: IndexerVersion) {
  require(backupVersion >= activeVersion)
  def getVersionByStatus(service: ServiceDiscovery): IndexerVersion = if (service.hasBackupCapability) backupVersion else activeVersion
  def getVersionsForCleanup(): Seq[IndexerVersion] = (0 until activeVersion.value).map { v => IndexerVersion(v) }
}

object IndexerVersionProviders {
  case object Article extends IndexerVersionProvider(3, 4)
  case object URIGraph extends IndexerVersionProvider(0, 0)
  case object Collection extends IndexerVersionProvider(0, 0)
  case object User extends IndexerVersionProvider(4, 4)
  case object UserGraph extends IndexerVersionProvider(0, 0)
  case object SearchFriend extends IndexerVersionProvider(0, 0)
  case object Message extends IndexerVersionProvider(0, 0)
  case object Phrase extends IndexerVersionProvider(0, 0)
  case object Library extends IndexerVersionProvider(9, 9)
  case object LibraryMembership extends IndexerVersionProvider(1, 1)
  case object Keep extends IndexerVersionProvider(2, 2)
}

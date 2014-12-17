package com.keepit.search.index

import com.keepit.search.index.sharding._
import com.keepit.common.util.Configuration

case class FakeIndexModule() extends IndexModule {

  protected def removeOldIndexDirs(conf: Configuration, configName: String, shard: Shard[_], versionsToClean: Seq[IndexerVersion]): Unit = {}
  protected def getIndexDirectory(configName: String, shard: Shard[_], version: IndexerVersion, indexStore: IndexStore, conf: Configuration, versionsToClean: Seq[IndexerVersion]): IndexDirectory = new VolatileIndexDirectory()

}

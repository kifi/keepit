package com.keepit.search.index

import com.keepit.search.sharding._
import com.keepit.common.util.Configuration

case class FakeIndexModule() extends IndexModule {

  protected def getIndexDirectory(configName: String, shard: Shard[_], version: IndexerVersion, indexStore: IndexStore, conf: Configuration): IndexDirectory = new VolatileIndexDirectory()

}

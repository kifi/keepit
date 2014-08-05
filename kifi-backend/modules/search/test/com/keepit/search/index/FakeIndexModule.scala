package com.keepit.search.index

import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.amazon.MyInstanceInfo
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.inject.AppScoped
import com.keepit.model.NormalizedURI
import com.keepit.search.ArticleStore
import com.keepit.search.article._
import com.keepit.search.graph._
import com.keepit.search.graph.collection._
import com.keepit.search.graph.bookmark._
import com.keepit.search.graph.user._
import com.keepit.search.message.{ MessageIndexer, MessageIndexerPlugin, MessageIndexerPluginImpl }
import com.keepit.search.phrasedetector.{ PhraseIndexerPluginImpl, PhraseIndexerPlugin, PhraseIndexerImpl, PhraseIndexer }
import com.keepit.search.sharding._
import com.keepit.search.spellcheck.{ SpellIndexerPlugin, SpellIndexerPluginImpl, SpellIndexer }
import com.keepit.search.user._
import com.keepit.shoebox.ShoeboxServiceClient
import com.google.inject.{ Provides, Singleton }
import org.apache.commons.io.FileUtils
import java.io.File
import com.keepit.search.graph.library.{ LibraryIndexerPluginImpl, LibraryIndexerPlugin, LibraryIndexer }
import com.keepit.search.graph.keep.{ KeepIndexer, KeepIndexerPluginImpl, KeepIndexerPlugin, ShardedKeepIndexer }
import com.keepit.common.util.Configuration

case class FakeIndexModule() extends IndexModule {

  protected def getIndexDirectory(configName: String, shard: Shard[_], indexStore: IndexStore, conf: Configuration): IndexDirectory = new VolatileIndexDirectory()

}

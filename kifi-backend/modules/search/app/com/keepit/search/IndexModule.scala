package com.keepit.search

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import com.keepit.search.graph._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.index._
import play.api.Play._
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import com.keepit.search.comment.{CommentIndexerPluginImpl, CommentIndexerPlugin, CommentIndexer, CommentStore}
import com.keepit.search.phrasedetector.{PhraseIndexerImpl, PhraseIndexer}
import com.keepit.search.query.parser.{FakeSpellCorrector, SpellCorrector}
import com.keepit.inject.AppScoped
import java.io.File
import com.keepit.common.logging.Logging
import com.keepit.search.user.UserIndexer
import com.keepit.search.user.UserIndexerPlugin
import com.keepit.search.user.UserIndexerPluginImpl
import com.keepit.common.time._
import org.apache.commons.io.FileUtils

trait IndexModule extends ScalaModule with Logging {

  protected def getPersistentIndexDirectory(maybeDir: Option[String], indexStore: IndexStore): Option[IndexDirectory] = {
    maybeDir.map { d =>
      val dir = new File(d).getCanonicalFile
      val indexDirectory = new IndexDirectoryImpl(dir, indexStore)
      if (!dir.exists()) {
        try {
          val t1 = currentDateTime.getMillis
          indexDirectory.restoreFromBackup()
          val t2 = currentDateTime.getMillis
          log.info(s"$d was restored from S3 in ${(t2 - t1) / 1000} seconds")
        }
        catch { case e: Exception => {
          log.error(s"Could not restore $dir from backup with id ${indexDirectory.getLockID}", e)
          FileUtils.deleteDirectory(dir)
          if (!dir.mkdirs()) {
            throw new Exception(s"Could not create directory $dir")
          }
        }}
      }
      indexDirectory
    }
  }

  protected def getIndexDirectory(maybeDir: Option[String], indexStore: IndexStore): IndexDirectory

  def configure() {
    bind[ArticleIndexerPlugin].to[ArticleIndexerPluginImpl].in[AppScoped]
    bind[URIGraphPlugin].to[URIGraphPluginImpl].in[AppScoped]
    bind[CommentIndexerPlugin].to[CommentIndexerPluginImpl].in[AppScoped]
    bind[UserIndexerPlugin].to[UserIndexerPluginImpl].in[AppScoped]
  }

  @Singleton
  @Provides
  def articleIndexer(articleStore: ArticleStore, uriGraph: URIGraph, backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): ArticleIndexer = {
    val dir = getIndexDirectory(current.configuration.getString("index.article.directory"), backup)
    log.info(s"storing search index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new ArticleIndexer(dir, config, articleStore, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def userIndexer(airbrake: AirbrakeNotifier, backup: IndexStore, shoeboxClient: ShoeboxServiceClient): UserIndexer = {
    val dir = getIndexDirectory(current.configuration.getString("index.user.directory"), backup)
    log.info(s"storing user index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new UserIndexer(dir, config, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def bookmarkStore(backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): BookmarkStore = {
    val dir = getIndexDirectory(current.configuration.getString("index.bookmarkStore.directory"), backup)
    log.info(s"storing BookmarkStore in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new BookmarkStore(dir, config, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def uriGraphIndexer(bookmarkStore: BookmarkStore, backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): URIGraphIndexer = {
    val dir = getIndexDirectory(current.configuration.getString("index.urigraph.directory"), backup)
    log.info(s"storing URIGraph in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new URIGraphIndexer(dir, config, bookmarkStore, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def collectionNameIndexer(airbrake: AirbrakeNotifier, backup: IndexStore, shoeboxClient: ShoeboxServiceClient): CollectionNameIndexer = {
    val dir = getIndexDirectory(current.configuration.getString("index.collectionName.directory"), backup)
    log.info(s"storing collection index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new CollectionNameIndexer(dir, config, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def collectionIndexer(collectionNameIndexer: CollectionNameIndexer, backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): CollectionIndexer = {
    val dir = getIndexDirectory(current.configuration.getString("index.collection.directory"), backup)
    log.info(s"storing collection index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new CollectionIndexer(dir, config, collectionNameIndexer, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def commentStore(backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): CommentStore = {
    val dir = getIndexDirectory(current.configuration.getString("index.commentStore.directory"), backup)
    log.info(s"storing CommentStore in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new CommentStore(dir, config, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def commentIndexer(commentStore: CommentStore, backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): CommentIndexer = {
    val dir = getIndexDirectory(current.configuration.getString("index.comment.directory"), backup)
    log.info(s"storing comment index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new CommentIndexer(dir, config, commentStore, airbrake, shoeboxClient)
  }

  @Singleton
  @Provides
  def phraseIndexer(backup: IndexStore, airbrake: AirbrakeNotifier, shoeboxClient: ShoeboxServiceClient): PhraseIndexer = {
    val dir = getIndexDirectory(current.configuration.getString("index.phrase.directory"), backup)
    val dataDir = current.configuration.getString("index.config").map{ path =>
      val configDir = new File(path).getCanonicalFile()
      new File(configDir, "phrase")
    }
    val analyzer = DefaultAnalyzer.forIndexing
    val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)
    new PhraseIndexerImpl(dir, config, airbrake, shoeboxClient)
  }
}

case class ProdIndexModule() extends IndexModule {

  protected def getIndexDirectory(maybeDir: Option[String], indexStore: IndexStore): IndexDirectory =
    getPersistentIndexDirectory(maybeDir, indexStore).get

  @Singleton
  @Provides
  def spellCorrector(backup: IndexStore): SpellCorrector = {
    val spellDir = getIndexDirectory(current.configuration.getString("index.spell.directory"), backup)
    val articleDir = getIndexDirectory(current.configuration.getString("index.article.directory"), backup)
    SpellCorrector(spellDir, articleDir)
  }
}

case class DevIndexModule() extends IndexModule {

  protected def getIndexDirectory(maybeDir: Option[String], indexStore: IndexStore): IndexDirectory =
    getPersistentIndexDirectory(maybeDir, indexStore).getOrElse(new VolatileIndexDirectoryImpl())

  @Singleton
  @Provides
  def spellCorrector(backup: IndexStore): SpellCorrector = {
    val spellDirOpt = getPersistentIndexDirectory(current.configuration.getString("index.spell.directory"), backup)
    val articleDirOpt = getPersistentIndexDirectory(current.configuration.getString("index.article.directory"), backup)

    (spellDirOpt, articleDirOpt) match {
      case (Some(sDir), Some(aDir)) => SpellCorrector(sDir, aDir)
      case _ => new FakeSpellCorrector
    }
  }
}

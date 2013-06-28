package com.keepit.search

import net.codingwell.scalaguice.ScalaModule
import org.apache.lucene.store.{MMapDirectory, Directory}
import com.google.inject.{Provides, Singleton}
import com.keepit.search.graph._
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.index.{ArticleIndexerPluginImpl, ArticleIndexerPlugin, DefaultAnalyzer, ArticleIndexer}
import play.api.Play._
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import com.keepit.search.comment.{CommentIndexerPluginImpl, CommentIndexerPlugin, CommentIndexer, CommentStore}
import com.keepit.search.phrasedetector.{PhraseIndexerImpl, PhraseIndexer}
import com.keepit.search.query.parser.SpellCorrector
import com.keepit.inject.AppScoped
import java.io.File
import com.keepit.common.logging.Logging

trait IndexModule extends ScalaModule

case class IndexImplModule() extends IndexModule with Logging {

  def configure() {
    bind[ArticleIndexerPlugin].to[ArticleIndexerPluginImpl].in[AppScoped]
    bind[URIGraphPlugin].to[URIGraphPluginImpl].in[AppScoped]
    bind[CommentIndexerPlugin].to[CommentIndexerPluginImpl].in[AppScoped]
  }

  private def getDirectory(maybeDir: Option[String]): Directory = {
    maybeDir.map { d =>
      val dir = new File(d).getCanonicalFile
      if (!dir.exists()) {
        if (!dir.mkdirs()) {
          throw new Exception(s"could not create dir $dir")
        }
      }
      new MMapDirectory(dir)
    }.get
  }

  @Singleton
  @Provides
  def articleIndexer(articleStore: ArticleStore, uriGraph: URIGraph, healthcheckPlugin: HealthcheckPlugin, shoeboxClient: ShoeboxServiceClient): ArticleIndexer = {
    val dir = getDirectory(current.configuration.getString("index.article.directory"))
    log.info(s"storing search index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new ArticleIndexer(dir, config, articleStore, healthcheckPlugin, shoeboxClient)
  }

  @Singleton
  @Provides
  def bookmarkStore(shoeboxClient: ShoeboxServiceClient): BookmarkStore = {
    val dir = getDirectory(current.configuration.getString("index.bookmarkStore.directory"))
    log.info(s"storing BookmarkStore in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new BookmarkStore(dir, config, shoeboxClient)
  }

  @Singleton
  @Provides
  def uriGraphIndexer(bookmarkStore: BookmarkStore, shoeboxClient: ShoeboxServiceClient): URIGraphIndexer = {
    val dir = getDirectory(current.configuration.getString("index.urigraph.directory"))
    log.info(s"storing URIGraph in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new URIGraphIndexer(dir, config, bookmarkStore, shoeboxClient)
  }

  @Singleton
  @Provides
  def collectionIndexer(shoeboxClient: ShoeboxServiceClient): CollectionIndexer = {
    val dir = getDirectory(current.configuration.getString("index.collection.directory"))
    log.info(s"storing collection index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new CollectionIndexer(dir, config, shoeboxClient)
  }

  @Singleton
  @Provides
  def commentStore(shoeboxClient: ShoeboxServiceClient): CommentStore = {
    val dir = getDirectory(current.configuration.getString("index.commentStore.directory"))
    log.info(s"storing CommentStore in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new CommentStore(dir, config, shoeboxClient)
  }

  @Singleton
  @Provides
  def commentIndexer(commentStore: CommentStore, shoeboxClient: ShoeboxServiceClient): CommentIndexer = {
    val dir = getDirectory(current.configuration.getString("index.comment.directory"))
    log.info(s"storing comment index in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new CommentIndexer(dir, config, commentStore, shoeboxClient)
  }

  @Singleton
  @Provides
  def phraseIndexer(shoeboxClient: ShoeboxServiceClient): PhraseIndexer = {
    val dir = getDirectory(current.configuration.getString("index.phrase.directory"))
    val dataDir = current.configuration.getString("index.config").map{ path =>
      val configDir = new File(path).getCanonicalFile()
      new File(configDir, "phrase")
    }
    val analyzer = DefaultAnalyzer.forIndexing
    val config = new IndexWriterConfig(Version.LUCENE_41, analyzer)
    new PhraseIndexerImpl(dir, config, shoeboxClient)

  }

  @Singleton
  @Provides
  def spellCorrector: SpellCorrector = {
    val spellDir = getDirectory(current.configuration.getString("index.spell.directory"))
    val articleDir = getDirectory(current.configuration.getString("index.article.directory"))
    SpellCorrector(spellDir, articleDir)
  }
}

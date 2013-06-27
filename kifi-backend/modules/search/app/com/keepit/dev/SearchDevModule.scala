package com.keepit.dev

import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.logging.Logging
import org.apache.lucene.store.{RAMDirectory, MMapDirectory, Directory}
import java.io.File
import com.google.inject.Provides
import com.keepit.search.{ArticleStore, ResultClickTracker}
import play.api.Play._
import scala.Some
import com.keepit.search.{ProbablisticLRU, InMemoryResultClickTrackerBuffer, FileResultClickTrackerBuffer}
import com.keepit.search.comment.{CommentIndexer, CommentStore}
import com.keepit.search.graph.{CollectionIndexer, URIGraphIndexer, BookmarkStore, URIGraph}
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.index.{DefaultAnalyzer, ArticleIndexer}
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import com.keepit.search.phrasedetector.{PhraseIndexerImpl, PhraseIndexer}
import com.keepit.search.query.parser.{FakeSpellCorrector, SpellCorrector}
import com.google.inject.Singleton

class SearchDevModule extends ScalaModule with Logging {
  def configure() {}

  private def getDirectory(maybeDir: Option[String]): Directory = {
    maybeDir.map { d =>
      val dir = new File(d).getCanonicalFile
      if (!dir.exists()) {
        if (!dir.mkdirs()) {
          throw new Exception(s"could not create dir $dir")
        }
      }
      new MMapDirectory(dir)
    }.getOrElse {
      new RAMDirectory()
    }
  }

  @Provides
  @Singleton
  def resultClickTracker: ResultClickTracker = {
    val conf = current.configuration.getConfig("result-click-tracker").get
    val numHashFuncs = conf.getInt("numHashFuncs").get
    val syncEvery = conf.getInt("syncEvery").get
    conf.getString("dir") match {
      case None =>
        val buffer = new InMemoryResultClickTrackerBuffer(1000)
        new ResultClickTracker(new ProbablisticLRU(buffer, numHashFuncs, Int.MaxValue))
      case Some(dirPath) =>
        val dir = new File(dirPath).getCanonicalFile()
        if (!dir.exists()) {
          if (!dir.mkdirs()) {
            throw new Exception("could not create dir %s".format(dir))
          }
        }
        val file = new File(dir, "resultclicks.plru")
        val buffer = new FileResultClickTrackerBuffer(file, 0x1000000)
        new ResultClickTracker(new ProbablisticLRU(buffer, numHashFuncs, syncEvery))
    }
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
  def spellCorrector : SpellCorrector = {
    val spellDir = getDirectory(current.configuration.getString("index.spell.directory"))
    val articleDir = getDirectory(current.configuration.getString("index.article.directory"))

    (spellDir, articleDir) match {
      case (sDir: MMapDirectory, aDir: MMapDirectory) => SpellCorrector(sDir, aDir)
      case _ => new FakeSpellCorrector
    }
  }
}
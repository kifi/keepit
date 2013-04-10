package com.keepit.search

import org.apache.lucene.util.Version
import com.google.inject.{Provides, Singleton}
import com.keepit.search.query.parser.SpellCorrector
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.inject.AppScoped
import com.keepit.model.PhraseRepo
import com.keepit.scraper.{ScraperPluginImpl, ScraperPlugin}
import com.keepit.search.graph.{URIGraphPluginImpl, URIGraphPlugin, URIGraph}
import com.keepit.search.index.{ArticleIndexerPluginImpl, ArticleIndexerPlugin, ArticleIndexer}
import com.keepit.search.phrasedetector.PhraseIndexer
import com.tzavellas.sse.guice.ScalaModule
import java.io.File
import org.apache.lucene.store.{Directory, MMapDirectory}
import play.api.Play.current
import com.keepit.search.graph.URIGraphDecoders
import org.apache.lucene.index.IndexWriterConfig
import com.keepit.search.graph.URIGraphImpl
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.model.BookmarkRepo

class SearchModule() extends ScalaModule with Logging {

  def configure() {
    bind[ArticleIndexerPlugin].to[ArticleIndexerPluginImpl].in[AppScoped]
    bind[URIGraphPlugin].to[URIGraphPluginImpl].in[AppScoped]
    bind[ScraperPlugin].to[ScraperPluginImpl].in[AppScoped]
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
  def articleIndexer(articleStore: ArticleStore, uriGraph: URIGraph): ArticleIndexer = {
    val dir = getDirectory(current.configuration.getString("index.article.directory"))
    log.info(s"storing search index in $dir")
    ArticleIndexer(dir, articleStore)
  }

  @Singleton
  @Provides
  def uriGraph(bookmarkRepo: BookmarkRepo,
    db: Database): URIGraph = {
    val dir = getDirectory(current.configuration.getString("index.urigraph.directory"))
    log.info(s"storing URIGraph in $dir")
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    new URIGraphImpl(dir, config, URIGraphDecoders.decoders(), bookmarkRepo, db)
  }

  @Singleton
  @Provides
  def phraseIndexer(db: Database, phraseRepo: PhraseRepo): PhraseIndexer = {
    val dir = getDirectory(current.configuration.getString("index.phrase.directory"))
    val dataDir = current.configuration.getString("index.config").map{ path =>
      val configDir = new File(path).getCanonicalFile()
      new File(configDir, "phrase")
    }
    PhraseIndexer(dir, db, phraseRepo)
  }

  @Singleton
  @Provides
  def spellCorrector: SpellCorrector = {
    val spellDir = getDirectory(current.configuration.getString("index.spell.directory"))
    val articleDir = getDirectory(current.configuration.getString("index.article.directory"))
    SpellCorrector(spellDir, articleDir)
  }

}

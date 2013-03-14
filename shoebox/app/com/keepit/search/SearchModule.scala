package com.keepit.search

import com.google.inject.{Provides, Singleton}
import com.keepit.common.cache.MemcachedCacheModule
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.inject.{FortyTwoModule, AppScoped}
import com.keepit.model.PhraseRepo
import com.keepit.module.{S3Module, SearchCommonModule}
import com.keepit.scraper._
import com.keepit.search._
import com.keepit.search.graph.{URIGraphPluginImpl, URIGraphPlugin, URIGraph}
import com.keepit.search.index.{ArticleIndexerPluginImpl, ArticleIndexerPlugin, ArticleIndexer}
import com.keepit.search.phrasedetector.PhraseIndexer
import com.tzavellas.sse.guice.ScalaModule
import java.io.File
import org.apache.http.HttpHost
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.lucene.store.{Directory, MMapDirectory}
import play.api.Play.current

class SearchModule() extends ScalaModule with Logging {

  def configure() {
    install(new FortyTwoModule)
    install(new MemcachedCacheModule)
    install(new SearchCommonModule)
    install(new S3Module)
    bind[ScraperPlugin].to[ScraperPluginImpl].in[AppScoped]
    bind[ArticleIndexerPlugin].to[ArticleIndexerPluginImpl].in[AppScoped]
    bind[URIGraphPlugin].to[URIGraphPluginImpl].in[AppScoped]
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
  def uriGraph: URIGraph = {
    val dir = getDirectory(current.configuration.getString("index.urigraph.directory"))
    log.info(s"storing URIGraph in $dir")
    URIGraph(dir)
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
  def scraperConfig: ScraperConfig = ScraperConfig()

  @Singleton
  @Provides
  def httpFetcher: HttpFetcher = {
    new HttpFetcherImpl(
      userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_2) AppleWebKit/537.17 (KHTML, like Gecko) Chrome/24.0.1309.0 Safari/537.17",
      connectionTimeout = 30000,
      soTimeOut = 30000
    )
  }

}

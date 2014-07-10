package com.keepit.search.article

import com.keepit.common.db._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.Host
import com.keepit.common.net.URI
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.keepit.search.semantic.SemanticVectorBuilder
import java.io.StringReader
import scala.util.Success
import com.keepit.search.article.ArticleRecordSerializer._
import com.keepit.search.index.IndexDirectory
import com.keepit.search.index.Indexer
import com.keepit.search.index.Indexable
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.IndexInfo
import com.keepit.search.sharding.Shard
import com.keepit.search.util.MultiStringReader

class ArticleIndexer(
  indexDirectory: IndexDirectory,
  articleStore: ArticleStore,
  override val airbrake: AirbrakeNotifier)
    extends Indexer[NormalizedURI, NormalizedURI, ArticleIndexer](indexDirectory) {

  import ArticleIndexer.ArticleIndexable

  override val commitBatchSize = 1000

  override def onFailure(indexable: Indexable[NormalizedURI, NormalizedURI], e: Throwable) {
    airbrake.notify(s"Error indexing article from normalized uri ${indexable.id}", e)
    super.onFailure(indexable, e)
  }

  def update(name: String, uris: Seq[IndexableUri], shard: Shard[NormalizedURI]): Int = updateLock.synchronized {
    doUpdate("ArticleIndex" + name) {
      uris.foreach { u =>
        if (!shard.contains(u.id.get)) throw new Exception(s"URI (id=${u.id.get}) does not belong to this shard ($shard)")
      }
      uris.iterator.map(buildIndexable)
    }
  }

  def update(): Int = throw new UnsupportedOperationException()

  def buildIndexable(uri: IndexableUri): ArticleIndexable = {
    new ArticleIndexable(
      id = uri.id.get,
      sequenceNumber = uri.seq,
      isDeleted = ArticleIndexer.shouldDelete(uri),
      uri = uri,
      articleStore = articleStore,
      airbrake = airbrake)
  }

  override def indexInfos(name: String): Seq[IndexInfo] = {
    super.indexInfos("ArticleIndex" + name)
  }
}

object ArticleIndexer extends Logging {
  private[this] val toBeDeletedStates = Set[State[NormalizedURI]](ACTIVE, INACTIVE, UNSCRAPABLE, REDIRECTED)
  def shouldDelete(uri: IndexableUri): Boolean = toBeDeletedStates.contains(uri.state)

  class ArticleIndexable(
      override val id: Id[NormalizedURI],
      override val sequenceNumber: SequenceNumber[NormalizedURI],
      override val isDeleted: Boolean,
      val uri: IndexableUri,
      articleStore: ArticleStore,
      airbrake: AirbrakeNotifier) extends Indexable[NormalizedURI, NormalizedURI] {
    implicit def toReader(text: String) = new StringReader(text)

    private def getArticle(id: Id[NormalizedURI], maxRetry: Int, minSleepTime: Long): Option[Article] = {
      var sleepTime = minSleepTime
      var retry = maxRetry
      while (retry > 0) {
        try {
          return articleStore.get(id)
        } catch {
          case e: Throwable =>
        }
        log.info(s"failed to get article from ArticleStore. retry in {$sleepTime}ms")
        Thread.sleep(sleepTime)
        sleepTime *= 2 // exponential back off
        retry -= 1
      }
      try {
        articleStore.get(id)
      } catch {
        case e: Throwable =>
          log.error(s"failed to get article from ArticleStore id=${id}", e)
          airbrake.notify(s"failed to get article from ArticleStore id=${id}")
          None // skip this doc
      }
    }

    override def buildDocument = {
      val doc = super.buildDocument
      getArticle(id = uri.id.get, maxRetry = 5, minSleepTime = 1000) match {
        case Some(article) =>
          uri.restriction.map { reason =>
            doc.add(buildKeywordField(ArticleVisibility.restrictedTerm.field(), ArticleVisibility.restrictedTerm.text()))
          }
          val titleLang = article.titleLang.getOrElse(DefaultAnalyzer.defaultLang)
          val contentLang = article.contentLang.getOrElse(DefaultAnalyzer.defaultLang)
          doc.add(buildKeywordField("cl", contentLang.lang))
          doc.add(buildKeywordField("tl", titleLang.lang))

          val titleAnalyzer = DefaultAnalyzer.getAnalyzer(titleLang)
          val titleAnalyzerWithStemmer = DefaultAnalyzer.getAnalyzerWithStemmer(titleLang)
          val contentAnalyzer = DefaultAnalyzer.getAnalyzer(contentLang)
          val contentAnalyzerWithStemmer = DefaultAnalyzer.getAnalyzerWithStemmer(contentLang)

          val content = Array(
            article.content, "\n\n",
            article.description.getOrElse(""), "\n\n",
            article.keywords.getOrElse(""), "\n\n",
            article.media.getOrElse(""))
          val titleAndUrl = Array(article.title, "\n\n", urlToIndexableString(uri.url).getOrElse(""))

          doc.add(buildTextField("t", new MultiStringReader(titleAndUrl), titleAnalyzer))
          doc.add(buildTextField("ts", new MultiStringReader(titleAndUrl), titleAnalyzerWithStemmer))

          doc.add(buildTextField("c", new MultiStringReader(content), contentAnalyzer))
          doc.add(buildTextField("cs", new MultiStringReader(content), contentAnalyzerWithStemmer))

          val builder = new SemanticVectorBuilder(60)
          builder.load(titleAnalyzerWithStemmer.tokenStream("t", article.title))
          builder.load(contentAnalyzerWithStemmer.tokenStream("c", new MultiStringReader(content)))
          doc.add(buildSemanticVectorField("sv", builder))

          val parsedURI = URI.parse(uri.url)
          parsedURI.foreach { uri =>
            uri.host.foreach {
              case Host(domain @ _*) =>
                if (domain.nonEmpty) {
                  // index domain name
                  doc.add(buildIteratorField("site", (1 to domain.size).iterator) { n => domain.take(n).reverse.mkString(".") })
                }
            }
          }

          // home page
          parsedURI match {
            case Success(URI(_, _, Some(Host(domain @ _*)), _, path, None, None)) if (!path.isDefined || path == Some("/")) =>
              doc.add(buildTextField("home_page", domain.reverse.mkString(" "), DefaultAnalyzer.defaultAnalyzer))
            case _ =>
          }

          // media keyword field
          article.media.foreach { media =>
            doc.add(buildTextField("media", media, DefaultAnalyzer.defaultAnalyzer))
          }

          // store title and url in the index
          val r = ArticleRecord(article.title, uri.url, article.id)
          doc.add(buildBinaryDocValuesField("rec", r))

          doc
        case None => doc
      }
    }
  }
}

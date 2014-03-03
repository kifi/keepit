package com.keepit.search.article

import com.keepit.common.db._
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.logging.Logging
import com.keepit.common.net.Host
import com.keepit.common.net.URI
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.keepit.search.Lang
import com.keepit.search.semantic.SemanticVectorBuilder
import java.io.StringReader
import org.apache.lucene.index.IndexWriterConfig
import com.google.inject.Inject
import scala.util.Success
import com.keepit.search.article.ArticleRecordSerializer._
import com.keepit.search.index.IndexDirectory
import com.keepit.search.index.Indexer
import com.keepit.search.index.IndexWarmer
import com.keepit.search.index.Indexable
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.IndexInfo
import com.keepit.search.sharding.Shard


class ArticleIndexer(
    indexDirectory: IndexDirectory,
    indexWriterConfig: IndexWriterConfig,
    articleStore: ArticleStore,
    airbrake: AirbrakeNotifier)
  extends Indexer[NormalizedURI, NormalizedURI, ArticleIndexer](indexDirectory, indexWriterConfig) {

  import ArticleIndexer.ArticleIndexable

  override val indexWarmer = Some(new IndexWarmer(Seq("t", "ts", "c", "cs")))

  override val commitBatchSize = 1000

  override def onFailure(indexable: Indexable[NormalizedURI, NormalizedURI], e: Throwable) {
    airbrake.notify(s"Error indexing article from normalized uri ${indexable.id}", e)
    super.onFailure(indexable, e)
  }

  def update(name: String, uris: Seq[IndexableUri], shard: Shard[NormalizedURI]): Int = updateLock.synchronized {
    doUpdate("ArticleIndex" + name) {
      uris.foreach{ u =>
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
      articleStore = articleStore)
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
    articleStore: ArticleStore
  ) extends Indexable[NormalizedURI, NormalizedURI] {
    implicit def toReader(text: String) = new StringReader(text)

    private def enrichedContent(article: Article): String = {
      val c = article.description match {
        case Some(desc) => desc + "\n\n" + article.content
        case None => article.content
      }
      article.media match {
        case Some(media) => c + "\n\n" + media
        case None => c
      }
    }

    private def getArticle(id: Id[NormalizedURI], maxRetry: Int, minSleepTime: Long): Option[Article] = {
      var sleepTime = minSleepTime
      var retry = maxRetry
      while (retry > 0) {
        try {
          return articleStore.get(id)
        } catch {
          case e: Throwable =>
        }
        log.info("failed to get article from ArticleStore. retry in {$sleepTime}ms")
        Thread.sleep(sleepTime)
        sleepTime *= 2 // exponential back off
        retry -= 0
      }
      articleStore.get(id)
    }

    override def buildDocument = {
      val doc = super.buildDocument
      getArticle(id = uri.id.get, maxRetry = 5, minSleepTime = 1000) match {
        case Some(article) =>
          uri.restriction.map{ reason =>
            doc.add(buildKeywordField(ArticleVisibility.restrictedTerm.field(), ArticleVisibility.restrictedTerm.text()))
          }
          val titleLang = article.titleLang.getOrElse(Lang("en"))
          val contentLang = article.contentLang.getOrElse(Lang("en"))
          doc.add(buildKeywordField("cl", contentLang.lang))
          doc.add(buildKeywordField("tl", titleLang.lang))

          val titleAnalyzer = DefaultAnalyzer.forIndexing(titleLang)
          val titleAnalyzerWithStemmer = DefaultAnalyzer.forIndexingWithStemmer(titleLang)
          val contentAnalyzer = DefaultAnalyzer.forIndexing(contentLang)
          val contentAnalyzerWithStemmer = DefaultAnalyzer.forIndexingWithStemmer(contentLang)

          val content = (Seq(article.content) ++ article.description ++ article.keywords).mkString("\n\n")
          val titleAndUrl = article.title + " " + urlToIndexableString(uri.url)

          doc.add(buildTextField("t", titleAndUrl, titleAnalyzer))
          doc.add(buildTextField("ts", titleAndUrl, titleAnalyzerWithStemmer))

          doc.add(buildTextField("c", content, contentAnalyzer))
          doc.add(buildTextField("cs", content, contentAnalyzerWithStemmer))

          val builder = new SemanticVectorBuilder(60)
          builder.load(titleAnalyzerWithStemmer.tokenStream("t", article.title))
          builder.load(contentAnalyzerWithStemmer.tokenStream("c", content))
          doc.add(buildDocSemanticVectorField("docSv", builder))
          doc.add(buildSemanticVectorField("sv", builder))

          val parsedURI = URI.parse(uri.url)
          parsedURI.foreach{ uri =>
            uri.host.foreach{ case Host(domain @ _*) =>
              if (domain.nonEmpty) {
                // index domain name
                doc.add(buildIteratorField("site", (1 to domain.size).iterator){ n => domain.take(n).reverse.mkString(".") })
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
          article.media.foreach{ media =>
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

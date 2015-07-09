package com.keepit.search.index.article

import java.io.StringReader

import com.keepit.common.db.{ State }
import com.keepit.model.NormalizedURIStates._
import com.keepit.model.{ IndexableUri, NormalizedURI }
import com.keepit.rover.article.content.{ ArticleContentExtractor }
import com.keepit.rover.article.Article
import com.keepit.search.index.sharding.Shard
import com.keepit.search.util.MultiStringReader
import com.keepit.search.index.{ Searcher, FieldDecoder, Indexable, DefaultAnalyzer }

object ArticleFields {
  val titleField = "t"
  val titleStemmedField = "ts"
  val titleLangField = "tl"
  val contentField = "c"
  val contentStemmedField = "cs"
  val contentLangField = "cl"
  val siteField = "site"
  val homePageField = "home_page"
  val mediaField = "media"
  val recordField = "rec"
  val safeField = "safe"

  val textSearchFields = Set(titleField, titleStemmedField, contentField, contentStemmedField, siteField, homePageField, mediaField)

  val decoders: Map[String, FieldDecoder] = Map.empty

  object Safety {
    val field = "safety"
    val unsafe = "unsafe"
  }

}

object ArticleIndexable {
  private[this] val toBeDeletedStates = Set[State[NormalizedURI]](INACTIVE, REDIRECTED)
  def shouldDelete(uri: IndexableUri): Boolean = toBeDeletedStates.contains(uri.state) || !uri.shouldHaveContent
  def isSafe(searcher: Searcher, uriId: Long): Boolean = {
    searcher.getLongDocValue(ArticleFields.safeField, uriId).exists(_ > 0)
  }
}

case class ArticleIndexable(uri: IndexableUri, articles: Set[Article], shard: Shard[NormalizedURI]) extends Indexable[NormalizedURI, NormalizedURI] {
  val id = uri.id.get
  val sequenceNumber = uri.seq
  val isDeleted = ArticleIndexable.shouldDelete(uri) || !shard.contains(uri.id.get)

  implicit def toReader(text: String) = new StringReader(text)

  override def buildDocument = {
    import ArticleFields._

    val doc = super.buildDocument
    val articleContent = ArticleContentExtractor(articles)

    if (uri.restriction.isDefined) {
      doc.add(buildKeywordField(Safety.field, Safety.unsafe))
    }

    val safe = if (uri.restriction.isDefined) 0L else 1L
    doc.add(buildLongValueField(safeField, safe))

    val titleLang = articleContent.titleLang.getOrElse(DefaultAnalyzer.defaultLang)
    val titleAnalyzer = DefaultAnalyzer.getAnalyzer(titleLang)
    val titleAnalyzerWithStemmer = DefaultAnalyzer.getAnalyzerWithStemmer(titleLang)

    val titleAndUrl = Array(articleContent.title.getOrElse(""), "\n\n", urlToIndexableString(uri.url).getOrElse(""))

    doc.add(buildKeywordField(titleLangField, titleLang.lang))
    doc.add(buildTextField(titleField, new MultiStringReader(titleAndUrl), titleAnalyzer))
    doc.add(buildTextField(titleStemmedField, new MultiStringReader(titleAndUrl), titleAnalyzerWithStemmer))

    val contentLang = articleContent.contentLang.getOrElse(DefaultAnalyzer.defaultLang)
    val contentAnalyzer = DefaultAnalyzer.getAnalyzer(contentLang)
    val contentAnalyzerWithStemmer = DefaultAnalyzer.getAnalyzerWithStemmer(contentLang)

    val content = Array(
      articleContent.content.getOrElse(""), "\n\n",
      articleContent.description.getOrElse(""), "\n\n",
      articleContent.validatedKeywords.mkString(" "), "\n\n",
      articleContent.contentType.getOrElse(""))

    doc.add(buildKeywordField(contentLangField, contentLang.lang))
    doc.add(buildTextField(contentField, new MultiStringReader(content), contentAnalyzer))
    doc.add(buildTextField(contentStemmedField, new MultiStringReader(content), contentAnalyzerWithStemmer))

    buildDomainFields(uri.url, siteField, homePageField).foreach(doc.add)

    // media keyword field
    articleContent.contentType.foreach { media =>
      doc.add(buildTextField(mediaField, media, DefaultAnalyzer.defaultAnalyzer))
    }

    // store title and url in the index
    val r = ArticleRecord(articleContent.title, uri.url, uri.id.get)
    doc.add(buildBinaryDocValuesField(recordField, r))

    doc
  }
}
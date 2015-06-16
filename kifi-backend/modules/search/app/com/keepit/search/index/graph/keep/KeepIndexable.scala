package com.keepit.search.index.graph.keep

import com.keepit.common.strings._
import com.keepit.model.{ Hashtag, LibraryVisibility, NormalizedURI, Keep }
import com.keepit.search.index.{ FieldDecoder, DefaultAnalyzer, Indexable }
import com.keepit.search.{ LangDetector }
import com.keepit.search.index.sharding.Shard
import com.keepit.search.index.graph.library.LibraryFields
import com.keepit.search.util.MultiStringReader
import org.apache.lucene.index.Term

object KeepFields {
  val libraryField = "lib"
  val libraryIdField = "libId"
  val uriField = "uri"
  val uriIdField = "uriId"
  val uriDiscoverableField = "uriDisc"
  val userField = "user"
  val userIdField = "userId"
  val userDiscoverableField = "userDisc"
  val visibilityField = "v"
  val titleField = "t"
  val titleStemmedField = "ts"
  val contentField = "c"
  val contentStemmedField = "cs"
  val siteField = "site"
  val homePageField = "home_page"
  val keptAtField = "keptAt"
  val tagsField = "h"
  val tagsStemmedField = "hs"
  val tagsKeywordField = "tag"
  val recordField = "rec"

  val textSearchFields = Set(titleField, titleStemmedField, contentField, contentStemmedField, siteField, homePageField, tagsField, tagsStemmedField, tagsKeywordField)

  val decoders: Map[String, FieldDecoder] = Map.empty
}

case class KeepIndexable(keep: Keep, tags: Set[Hashtag], shard: Shard[NormalizedURI]) extends Indexable[Keep, Keep] {
  val id = keep.id.get
  val sequenceNumber = keep.seq
  val isDeleted = !keep.isActive || !shard.contains(keep.uriId)

  override def buildDocument = {
    import KeepFields._
    val doc = super.buildDocument

    doc.add(buildKeywordField(uriField, keep.uriId.toString))
    if (keep.visibility != LibraryVisibility.SECRET) doc.add(buildKeywordField(uriDiscoverableField, keep.uriId.toString))
    doc.add(buildKeywordField(userField, keep.userId.toString))
    if (keep.visibility != LibraryVisibility.SECRET) doc.add(buildKeywordField(userDiscoverableField, keep.userId.toString))

    val titleLang = keep.title.collect { case title if title.nonEmpty => LangDetector.detect(title) } getOrElse DefaultAnalyzer.defaultLang
    val titleAndUrl = Array(keep.title.getOrElse(""), "\n\n", urlToIndexableString(keep.url).getOrElse("")) // piggybacking uri text on title
    val titleAnalyzer = DefaultAnalyzer.getAnalyzer(titleLang)
    val titleAnalyzerWithStemmer = DefaultAnalyzer.getAnalyzerWithStemmer(titleLang)

    doc.add(buildTextField(titleField, new MultiStringReader(titleAndUrl), titleAnalyzer))
    doc.add(buildTextField(titleStemmedField, new MultiStringReader(titleAndUrl), titleAnalyzerWithStemmer))
    doc.add(buildDataPayloadField(new Term(libraryField, keep.libraryId.get.toString), titleLang.lang.getBytes(UTF8)))

    val contentLang = keep.note.collect { case note if note.nonEmpty => LangDetector.detect(note) } getOrElse DefaultAnalyzer.defaultLang
    val contentAnalyzer = DefaultAnalyzer.getAnalyzer(contentLang)
    val contentAnalyzerWithStemmer = DefaultAnalyzer.getAnalyzerWithStemmer(contentLang)

    val content = keep.note.getOrElse("")
    doc.add(buildTextField(contentField, content, contentAnalyzer))
    doc.add(buildTextField(contentStemmedField, content, contentAnalyzerWithStemmer))

    tags.foreach { tag =>
      val tagLang = LangDetector.detect(tag.tag)
      doc.add(buildTextField(tagsField, tag.tag, DefaultAnalyzer.getAnalyzer(tagLang)))
      doc.add(buildTextField(tagsStemmedField, tag.tag, DefaultAnalyzer.getAnalyzerWithStemmer(tagLang)))
      doc.add(buildKeywordField(tagsKeywordField, tag.tag.toLowerCase))
    }

    buildDomainFields(keep.url, siteField, homePageField).foreach(doc.add)

    doc.add(buildIdValueField(uriIdField, keep.uriId))
    doc.add(buildIdValueField(userIdField, keep.userId))
    keep.libraryId.foreach(libId => doc.add(buildIdValueField(libraryIdField, libId)))

    doc.add(buildLongValueField(visibilityField, LibraryFields.Visibility.toNumericCode(keep.visibility)))

    doc.add(buildBinaryDocValuesField(recordField, KeepRecord.fromKeepAndTags(keep, tags)))
    doc.add(buildLongValueField(keptAtField, keep.keptAt.getMillis))

    doc
  }
}

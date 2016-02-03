package com.keepit.search.index.graph.keep

import com.keepit.common.db.Id
import com.keepit.common.strings._
import com.keepit.model._
import com.keepit.search.index.{ Searcher, FieldDecoder, DefaultAnalyzer, Indexable }
import com.keepit.search.{ LangDetector }
import com.keepit.search.index.sharding.Shard
import com.keepit.search.index.graph.library.LibraryFields
import com.keepit.search.util.MultiStringReader
import com.keepit.slack.models.SlackChannelId
import com.keepit.social.twitter.TwitterHandle
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
  val orgField = "org"
  val orgIdField = "orgId"
  val orgDiscoverableField = "orgDisc"
  val visibilityField = "v"
  val titleField = "t"
  val titleStemmedField = "ts"
  val titlePrefixField = "tp"
  val titleValueField = "tv"
  val contentField = "c"
  val contentStemmedField = "cs"
  val sourceField = "source"
  val siteField = "site"
  val homePageField = "home_page"
  val keptAtField = "keptAt"
  val tagsField = "h"
  val tagsStemmedField = "hs"
  val tagsKeywordField = "tag"
  val recordField = "rec"

  val minimalSearchFields = Set(titleField, titleStemmedField, siteField, homePageField, tagsField, tagsStemmedField, tagsKeywordField)
  val fullTextSearchFields = Set(contentField, contentStemmedField)
  val prefixSearchFields = Set(titlePrefixField)

  val maxPrefixLength = 8

  object Source {
    def apply(channelId: SlackChannelId): String = s"slack|${channelId.value}"
    def apply(handle: TwitterHandle): String = s"twitter|${handle.value}"
    def apply(source: RawSourceAttribution): String = Source(SourceAttribution.fromRawSourceAttribution(source))
    def apply(source: SourceAttribution): String = source match {
      case TwitterAttribution(tweet) => Source(tweet.user.screenName)
      case SlackAttribution(message) => Source(message.channel.id)
    }
  }

  val decoders: Map[String, FieldDecoder] = Map.empty
}

object KeepIndexable {
  @inline
  def isDiscoverable(keepSearcher: Searcher, uriId: Long) = keepSearcher.has(new Term(KeepFields.uriDiscoverableField, uriId.toString))
}

case class KeepIndexable(keep: Keep, sourceAttribution: Option[RawSourceAttribution], tags: Set[Hashtag], shard: Shard[NormalizedURI]) extends Indexable[Keep, Keep] {
  val id = keep.id.get
  val sequenceNumber = keep.seq
  val isDeleted = !keep.isActive || !shard.contains(keep.uriId)

  override def buildDocument = {
    import KeepFields._
    val doc = super.buildDocument

    val titleLang = keep.title.collect { case title if title.nonEmpty => LangDetector.detect(title) } getOrElse DefaultAnalyzer.defaultLang
    val titleAndUrl = Array(keep.title.getOrElse(""), "\n\n", urlToIndexableString(keep.url).getOrElse("")) // piggybacking uri text on title
    val titleAnalyzer = DefaultAnalyzer.getAnalyzer(titleLang)
    val titleAnalyzerWithStemmer = DefaultAnalyzer.getAnalyzerWithStemmer(titleLang)

    doc.add(buildTextField(titleField, new MultiStringReader(titleAndUrl), titleAnalyzer))
    doc.add(buildTextField(titleStemmedField, new MultiStringReader(titleAndUrl), titleAnalyzerWithStemmer))
    doc.add(buildPrefixField(titlePrefixField, keep.title.getOrElse(""), maxPrefixLength))
    doc.add(buildStringDocValuesField(titleValueField, keep.title.getOrElse("")))

    val contentLang = keep.note.collect { case note if note.nonEmpty => LangDetector.detect(note) } getOrElse DefaultAnalyzer.defaultLang
    val contentAnalyzer = DefaultAnalyzer.getAnalyzer(contentLang)
    val contentAnalyzerWithStemmer = DefaultAnalyzer.getAnalyzerWithStemmer(contentLang)

    val content = keep.note.getOrElse("")
    doc.add(buildTextField(contentField, content, contentAnalyzer))
    doc.add(buildTextField(contentStemmedField, content, contentAnalyzerWithStemmer))

    sourceAttribution.foreach { source =>
      doc.add(buildKeywordField(sourceField, Source(source)))
    }

    tags.foreach { tag =>
      val tagLang = LangDetector.detect(tag.tag)
      doc.add(buildTextField(tagsField, tag.tag, DefaultAnalyzer.getAnalyzer(tagLang)))
      doc.add(buildTextField(tagsStemmedField, tag.tag, DefaultAnalyzer.getAnalyzerWithStemmer(tagLang)))
      doc.add(buildKeywordField(tagsKeywordField, tag.tag.toLowerCase))
    }

    buildDomainFields(keep.url, siteField, homePageField).foreach(doc.add)

    doc.add(buildKeywordField(uriField, keep.uriId.toString))
    doc.add(buildKeywordField(userField, keep.userId.toString))

    if (keep.visibility == LibraryVisibility.PUBLISHED || keep.visibility == LibraryVisibility.DISCOVERABLE) {
      doc.add(buildKeywordField(uriDiscoverableField, keep.uriId.toString))
      doc.add(buildKeywordField(userDiscoverableField, keep.userId.toString))
    }

    keep.organizationId.foreach { orgId =>
      doc.add(buildKeywordField(orgField, orgId.toString))
      if (keep.visibility == LibraryVisibility.PUBLISHED || keep.visibility == LibraryVisibility.ORGANIZATION) {
        doc.add(buildKeywordField(orgDiscoverableField, keep.organizationId.get.id.toString))
      }
    }

    keep.libraryId.foreach { libId =>
      doc.add(buildDataPayloadField(new Term(libraryField, libId.id.toString), titleLang.lang.getBytes(UTF8)))
    }

    doc.add(buildIdValueField(uriIdField, keep.uriId))
    doc.add(buildIdValueField(userIdField, keep.userId))
    doc.add(buildIdValueField(libraryIdField, keep.libraryId.getOrElse(Id[Library](-1))))
    doc.add(buildIdValueField(orgIdField, keep.organizationId.getOrElse(Id[Organization](-1))))

    doc.add(buildLongValueField(visibilityField, LibraryFields.Visibility.toNumericCode(keep.visibility)))

    doc.add(buildBinaryDocValuesField(recordField, KeepRecord.fromKeepAndTags(keep, tags)))
    doc.add(buildLongValueField(keptAtField, keep.keptAt.getMillis))

    doc
  }
}

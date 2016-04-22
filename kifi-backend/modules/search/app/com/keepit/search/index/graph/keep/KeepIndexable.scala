package com.keepit.search.index.graph.keep

import com.keepit.common.db.Id
import com.keepit.common.strings._
import com.keepit.model._
import com.keepit.search.index.{ Searcher, FieldDecoder, DefaultAnalyzer, Indexable }
import com.keepit.search.{ LangDetector }
import com.keepit.search.util.MultiStringReader
import com.keepit.slack.models.{ SlackTeamId, SlackChannelId }
import com.keepit.social.twitter.TwitterHandle
import org.apache.lucene.index.Term

object KeepFields {
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
  val lastActivityAt = "lastActivityAt"
  val tagsField = "h"
  val tagsStemmedField = "hs"
  val tagsKeywordField = "tag"
  val recordField = "rec"

  val uriField = "uri"
  val uriIdField = "uriId"
  val ownerField = "owner"
  val ownerIdField = "ownerId"
  val userField = "user"
  val userIdsField = "userIds"
  val libraryField = "lib"
  val libraryIdsField = "libIds"

  // The following fields are derived from libraries
  val orgField = "org"
  val orgIdsField = "orgIds"
  val orgDiscoverableField = "orgDisc"
  val orgIdsDiscoverableField = "orgIdsDisc"
  val userDiscoverableField = "userDisc"
  val userIdsDiscoverableField = "userIdsDisc"
  val published = "published"
  val uriDiscoverableField = "uriDisc"

  val minimalSearchFields = Set(titleField, titleStemmedField, siteField, homePageField, tagsField, tagsStemmedField, tagsKeywordField)
  val fullTextSearchFields = Set(contentField, contentStemmedField)
  val prefixSearchFields = Set(titlePrefixField)

  val maxPrefixLength = 8

  object Source {
    def apply(teamId: SlackTeamId, channelId: SlackChannelId): String = s"slack|${channelId.value}" // TODO(Léo): this should be "slack|teamId_channelId"
    def apply(handle: TwitterHandle): String = s"twitter|${handle.value}"
    def apply(source: KeepSource): String = s"kifi|${source.value}"
    def apply(source: SourceAttribution): String = source match {
      case TwitterAttribution(tweet) => Source(tweet.user.screenName)
      case SlackAttribution(message, teamId) => Source(teamId, message.channel.id)
      case KifiAttribution(_, _, _, _, _, keepSource) => Source(keepSource)
    }
  }

  val decoders: Map[String, FieldDecoder] = Map.empty
}

object KeepIndexable {
  @inline
  def isDiscoverable(keepSearcher: Searcher, uriId: Long) = keepSearcher.has(new Term(KeepFields.uriDiscoverableField, uriId.toString))
}

case class KeepIndexable(keep: CrossServiceKeep, sourceAttribution: Option[SourceAttribution], tags: Set[Hashtag]) extends Indexable[Keep, Keep] {
  val id = keep.id
  val sequenceNumber = keep.seq
  val isDeleted = !keep.isActive

  override def buildDocument = {
    import KeepFields._
    val doc = super.buildDocument

    val titleLang = keep.title.collect { case title if title.nonEmpty => LangDetector.detect(title) } getOrElse DefaultAnalyzer.defaultLang
    val titleAndUrl = Array(keep.title.getOrElse(""), "\n\n", Indexable.urlToIndexableString(keep.url).getOrElse("")) // piggybacking uri text on title
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

    doc.add(buildBinaryDocValuesField(recordField, KeepRecord.fromKeepAndTags(keep, tags)))
    doc.add(buildLongValueField(keptAtField, keep.keptAt.getMillis))
    doc.add(buildLongValueField(lastActivityAt, keep.lastActivityAt.getMillis))

    doc.add(buildKeywordField(uriField, keep.uriId.id.toString))
    doc.add(buildIdValueField(uriIdField, keep.uriId))

    keep.owner.foreach { ownerId => doc.add(buildKeywordField(ownerField, ownerId.id.toString)) }
    doc.add(buildIdValueField(ownerIdField, keep.owner.getOrElse(Id[User](-1))))

    keep.users.foreach { userId => doc.add(buildKeywordField(userField, userId.id.toString)) }
    doc.add(buildIdSetValueField(userIdsField, keep.users))

    val libraryIds = keep.libraries.map(_.id)
    libraryIds.foreach { libraryId => doc.add(buildDataPayloadField(new Term(libraryField, libraryId.id.toString), titleLang.lang.getBytes(UTF8))) }
    doc.add(buildIdSetValueField(libraryIdsField, libraryIds))

    val organizationIds = keep.libraries.flatMap(_.organizationId)
    organizationIds.foreach { orgId => doc.add(buildKeywordField(orgField, orgId.id.toString)) }
    doc.add(buildIdSetValueField(orgIdsField, organizationIds))

    val discoverableOrganizationIds = keep.libraries.flatMap { library =>
      if (library.visibility == LibraryVisibility.PUBLISHED || library.visibility == LibraryVisibility.ORGANIZATION) library.organizationId
      else None
    }
    discoverableOrganizationIds.foreach { orgId => doc.add(buildKeywordField(orgDiscoverableField, orgId.id.toString)) }
    doc.add(buildIdSetValueField(orgIdsDiscoverableField, discoverableOrganizationIds))

    val discoverableUserIds = keep.libraries.flatMap { library =>
      if (library.visibility == LibraryVisibility.PUBLISHED || library.visibility == LibraryVisibility.DISCOVERABLE) library.addedBy
      else None
    }
    discoverableUserIds.foreach(orgId => doc.add(buildKeywordField(userDiscoverableField, orgId.id.toString)))
    doc.add(buildIdSetValueField(userIdsDiscoverableField, discoverableUserIds))

    val isPublished = keep.libraries.exists(_.visibility == LibraryVisibility.PUBLISHED)
    doc.add(buildLongValueField(published, if (isPublished) 1L else 0L))

    val isDiscoverable = isPublished || keep.libraries.exists(_.visibility == LibraryVisibility.DISCOVERABLE)
    if (isDiscoverable) { doc.add(buildKeywordField(uriDiscoverableField, keep.uriId.id.toString)) }

    doc
  }
}

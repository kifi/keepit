package com.keepit.search.index.message

import com.keepit.search.{ Lang, LangDetector }
import com.keepit.search.IndexInfo
import com.keepit.search.index.{ Indexer, Indexable, DefaultAnalyzer, IndexDirectory, LineFieldBuilder }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.net.{ URI, Host }
import com.keepit.common.strings.UTF8
import com.keepit.eliza.ElizaServiceClient
import org.apache.lucene.document.Document
import play.api.libs.json.Json
import scala.concurrent.Await
import scala.concurrent.duration._
import java.io.StringReader
import com.keepit.social.{ BasicUser, BasicNonUser }

object ThreadIndexFields {
  var titleField = "mt_title"
  var titleStemmedField = "mt_title_stemmed"
  val urlField = "mt_url"
  val urlKeywordField = "mt_url_kw"
  val participantNameField = "mt_participant_name"
  val contentField = "mt_content"
  val contentStemmedField = "mt_content_stemmed"
  val participantIdsField = "mt_participant_ids"
  val resultField = "mt_result"
  val updatedAtField = "mt_updated_at"
}

class MessageContentIndexable(
    val data: ThreadContent,
    val id: Id[ThreadContent],
    val sequenceNumber: SequenceNumber[ThreadContent],
    val airbrake: AirbrakeNotifier,
    val isDeleted: Boolean = false) extends Indexable[ThreadContent, ThreadContent] with LineFieldBuilder {

  override def buildDocument: Document = {
    val doc = super.buildDocument
    val preferedLang = Lang("en")

    //add the content
    val threadContentList = (0 until data.content.length).map { i =>
      val message = data.content(i)
      val messageLang = LangDetector.detect(message, preferedLang)
      (i, message, messageLang)
    }

    val content = buildLineField(ThreadIndexFields.contentField, threadContentList) { (fieldName, text, lang) =>
      val analyzer = DefaultAnalyzer.getAnalyzer(lang)
      analyzer.tokenStream(fieldName, new StringReader(text))
    }
    doc.add(content)

    val contentStemmed = buildLineField(ThreadIndexFields.contentStemmedField, threadContentList) { (fieldName, text, lang) =>
      val analyzer = DefaultAnalyzer.getAnalyzerWithStemmer(lang)
      analyzer.tokenStream(fieldName, new StringReader(text))
    }
    doc.add(contentStemmed)

    //title
    val titleText = data.pageTitleOpt.getOrElse("")
    val titleLang = LangDetector.detect(titleText, preferedLang)
    val titleAnalyzer = DefaultAnalyzer.getAnalyzer(titleLang)
    val titleAnalyzerWithStemmer = DefaultAnalyzer.getAnalyzerWithStemmer(titleLang)
    val pageTitle = buildTextField(ThreadIndexFields.titleField, titleText, titleAnalyzer)
    doc.add(pageTitle)
    val pageTitleStemmed = buildTextField(ThreadIndexFields.titleStemmedField, titleText, titleAnalyzerWithStemmer)
    doc.add(pageTitleStemmed)

    //add the url
    URI.parse(data.url).toOption.flatMap(_.host) match {
      case Some(Host(domain @ _*)) =>
        doc.add(buildIteratorField(ThreadIndexFields.urlField, (1 to domain.size).iterator) { n => domain.take(n).reverse.mkString(".") })
        doc.add(buildIteratorField(ThreadIndexFields.urlKeywordField, (0 until domain.size).iterator)(domain))
      case _ =>
    }

    //add the participant names
    val participantNameList = (0 until data.participants.length).map { i =>
      val participant = data.participants(i)
      val participantName = participant match {
        case user: BasicUser => user.firstName + " " + user.lastName
        case nonUser: BasicNonUser => {
          val fullNameOpt = for {
            firstName <- nonUser.firstName
            lastName <- nonUser.lastName
          } yield firstName + " " + lastName
          fullNameOpt getOrElse nonUser.id
        }
      }
      val participantNameLang = LangDetector.detect(participantName, preferedLang)
      (i, participantName, participantNameLang)
    }
    val participantNames = buildLineField(ThreadIndexFields.participantNameField, participantNameList) { (fieldName, text, lang) =>
      val analyzer = DefaultAnalyzer.getAnalyzer(lang)
      analyzer.tokenStream(fieldName, new StringReader(text))
    }
    doc.add(participantNames)

    //participant Ids
    doc.add(buildIteratorField(ThreadIndexFields.participantIdsField, data.participantIds.iterator) { id => id.toString })

    //timestamp for time decay
    doc.add(buildLongValueField(ThreadIndexFields.updatedAtField, data.updatedAt.getMillis))

    //search result json, which will be served in the results verbatim
    val pageTitleOrUrl: String = data.pageTitleOpt.getOrElse(data.url)
    val searchResultJson = Json.obj(
      "participants" -> data.participants,
      "title" -> pageTitleOrUrl,
      "digest" -> data.digest,
      "time" -> data.updatedAt,
      "url" -> data.url,
      "thread" -> data.threadExternalId
    )
    val searchResultData = Json.stringify(searchResultJson).getBytes(UTF8)
    val searchResultDocValue = buildBinaryDocValuesField(ThreadIndexFields.resultField, searchResultData)
    if (searchResultData.length < 30000) {
      doc.add(searchResultDocValue)
    } else {
      val fakeResultData = Json.stringify(Json.obj("err" -> "result too large")).getBytes(UTF8)
      doc.add(buildBinaryDocValuesField(ThreadIndexFields.resultField, fakeResultData))
      airbrake.notify(s"Failed to index thread ${data.threadExternalId} correctly. Result json would be too large.")
    }

    doc
  }

}

class MessageIndexer(
  indexDirectory: IndexDirectory,
  eliza: ElizaServiceClient,
  override val airbrake: AirbrakeNotifier)
    extends Indexer[ThreadContent, ThreadContent, MessageIndexer](indexDirectory) {

  val loadBatchSize: Int = 100
  override val commitBatchSize: Int = 50

  def update(): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()

    var total = 0
    var done = false
    while (!done) {
      total += doUpdate("MessageIndex") {
        val batch = Await.result(eliza.getThreadContentForIndexing(sequenceNumber, loadBatchSize), 60 seconds)
        done = batch.length <= 1
        batch.iterator.map { threadContent =>
          new MessageContentIndexable(
            data = threadContent,
            id = threadContent.id,
            sequenceNumber = threadContent.seq,
            airbrake = airbrake
          )
        }
      }
    }
    total
  }

  override def indexInfos(name: String): Seq[IndexInfo] = {
    super.indexInfos("MessageIndex" + name)
  }
}


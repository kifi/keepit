package com.keepit.search.message

import com.keepit.search.index.{Indexer, Indexable, DefaultAnalyzer, IndexDirectory}
import com.keepit.search.{Lang, LangDetector}
import com.keepit.search.line.LineFieldBuilder
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.{Id, SequenceNumber}
import com.keepit.common.net.{URI, Host}
import com.keepit.common.strings.UTF8
import com.keepit.social.BasicUser
import com.keepit.model.User

import org.apache.lucene.store.Directory
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.document.Document

import play.api.libs.json.Json

import org.joda.time.DateTime

import java.io.StringReader




sealed trait ThreadContentUpdateMode 
case object DIFF extends ThreadContentUpdateMode //ZZZ not supported yet
case object FULL extends ThreadContentUpdateMode


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
  val resultLengthField = "mt_result_length"
}

case class ThreadContent( //ZZZ here for testing, will move to common, to be used by Eliza and Search
  mode: ThreadContentUpdateMode,
  id: Id[ThreadContent],
  participants: Seq[BasicUser],
  updatedAt: DateTime,
  url: String,
  threadExternalId: String,
  pageTitleOpt: Option[String],
  digest: String,
  content: Seq[(BasicUser,String)],
  participantIds: Set[Id[User]] 
)

class MessageContentIndexable(
    val data: ThreadContent,
    val id: Id[ThreadContent],
    val sequenceNumber: SequenceNumber,
    val isDeleted: Boolean = false
  ) extends Indexable[ThreadContent] with LineFieldBuilder{

  override def buildDocument: Document = {
    val doc = super.buildDocument
    val preferedLang = Lang("en")  

    //add the content 
    val threadContentList = (0 until data.content.length).map{ i =>
      val (sender, message) = data.content(i)
      val senderName = sender.firstName + sender.lastName
      val messageLang = LangDetector.detect(message, preferedLang)
      (i, (senderName, message), messageLang)
    }

    val content = buildLineField(ThreadIndexFields.contentField, threadContentList){ (fieldName, userAndText, lang) =>
      val analyzer = DefaultAnalyzer.forIndexing(lang)
      analyzer.tokenStream(fieldName, new StringReader(userAndText._1 + "\n\n" + userAndText._2))
    }
    doc.add(content)

    val contentStemmed = buildLineField(ThreadIndexFields.contentStemmedField, threadContentList){ (fieldName, userAndText, lang) =>
      val analyzer = DefaultAnalyzer.forIndexingWithStemmer(lang)
      analyzer.tokenStream(fieldName, new StringReader(userAndText._2))
    }
    doc.add(contentStemmed)

    //title
    val titleText = data.pageTitleOpt.getOrElse("")
    val titleLang = LangDetector.detect(titleText, preferedLang)
    val titleAnalyzer = DefaultAnalyzer.forIndexing(titleLang)
    val titleAnalyzerWithStemmer = DefaultAnalyzer.forIndexingWithStemmer(titleLang)
    val pageTitle = buildTextField(ThreadIndexFields.titleField, titleText, titleAnalyzer)
    doc.add(pageTitle)
    val pageTitleStemmed = buildTextField(ThreadIndexFields.titleStemmedField, titleText, titleAnalyzerWithStemmer)
    doc.add(pageTitleStemmed)

    //add the url
    URI.parse(data.url).toOption.flatMap(_.host) match {
      case Some(Host(domain @ _*)) =>
        doc.add(buildIteratorField(ThreadIndexFields.urlField, (1 to domain.size).iterator){ n => domain.take(n).reverse.mkString(".") })
        doc.add(buildIteratorField(ThreadIndexFields.urlKeywordField, (0 until domain.size).iterator)(domain))
      case _ =>
    }

    //add the participant names
    val participantNameList = (0 until data.participants.length).map{ i=>
      val user = data.participants(i)
      val userName = user.firstName + " " + user.lastName
      val userNameLang = LangDetector.detect(userName, preferedLang)
      (i, userName, userNameLang)
    }
    val participantNames = buildLineField(ThreadIndexFields.participantNameField, participantNameList){ (fieldName, text, lang) =>
      val analyzer = DefaultAnalyzer.forIndexing(lang)
      analyzer.tokenStream(fieldName, new StringReader(text))
    }
    doc.add(participantNames)

    //participant Ids
    doc.add(buildIteratorField(ThreadIndexFields.participantIdsField, data.participantIds.iterator){ id => id.toString })

    //search result json, which will be served in the results verbatim
    val pageTitleOrUrl: String = data.pageTitleOpt.getOrElse(data.url)
    val searchResultJson = Json.obj(
      "participants" -> data.participants,
      "title"        -> pageTitleOrUrl,
      "digest"       -> data.digest,
      "time"         -> data.updatedAt,
      "url"          -> data.url,
      "thread"       -> data.threadExternalId
    )
    val searchResultData = Json.stringify(searchResultJson).getBytes(UTF8)
    val searchResultDocValue = buildBinaryDocValuesField(ThreadIndexFields.resultField,searchResultData)
    doc.add(buildLongValueField(ThreadIndexFields.resultLengthField, searchResultData.length))
    doc.add(searchResultDocValue)

    doc
  }

}

class MessageIndexer(
    indexDirectory: IndexDirectory,
    indexWriterConfig: IndexWriterConfig,
    airbrake: AirbrakeNotifier)
  extends Indexer[ThreadContent](indexDirectory, indexWriterConfig) {
  
    def update() = { //ZZZ Todo
      resetSequenceNumberIfReindex()
      //lock?
      //get things to index from eliza given sequence number and batch size
      //convert them to an indexable
      //call indexDocuments( Iterbale[Indexable[MessageContent]], commitBatchSize: Int)
    }

  }




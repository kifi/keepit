package com.keepit.search.message

import com.keepit.search.index.{Indexer, Indexable, DefaultAnalyzer, IndexDirectory}
import com.keepit.search.{Lang, LangDetector}
import com.keepit.search.line.LineFieldBuilder
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.{Id, SequenceNumber}
import com.keepit.common.net.{URI, Host}
import com.keepit.common.strings.UTF8
import com.keepit.eliza.ElizaServiceClient

import org.apache.lucene.store.Directory
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.document.Document

import play.api.libs.json.Json

import scala.concurrent.Await
import scala.concurrent.duration._

import java.io.StringReader


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
      val message = data.content(i)
      val messageLang = LangDetector.detect(message, preferedLang)
      (i, message, messageLang)
    }

    val content = buildLineField(ThreadIndexFields.contentField, threadContentList){ (fieldName, text, lang) =>
      val analyzer = DefaultAnalyzer.forIndexing(lang)
      analyzer.tokenStream(fieldName, new StringReader(text))
    }
    doc.add(content)

    val contentStemmed = buildLineField(ThreadIndexFields.contentStemmedField, threadContentList){ (fieldName, text, lang) =>
      val analyzer = DefaultAnalyzer.forIndexingWithStemmer(lang)
      analyzer.tokenStream(fieldName, new StringReader(text))
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
    if (searchResultData.length < 20000) { //ZZZ TEMPORARY 
      doc.add(buildLongValueField(ThreadIndexFields.resultLengthField, searchResultData.length))
      doc.add(searchResultDocValue)
    } else {
      val fakeResultData = Json.stringify(Json.obj()).getBytes(UTF8)
      doc.add(buildLongValueField(ThreadIndexFields.resultLengthField, fakeResultData.length))
      doc.add(buildBinaryDocValuesField(ThreadIndexFields.resultField,fakeResultData))
    }

    doc
  }

}

class MessageIndexer(
    indexDirectory: IndexDirectory,
    indexWriterConfig: IndexWriterConfig,
    eliza: ElizaServiceClient,
    airbrake: AirbrakeNotifier)
  extends Indexer[ThreadContent](indexDirectory, indexWriterConfig) {

    var indexingInProgress : Boolean = false
    val loadBatchSize : Int = 100
    val commitBatchSize : Int = 50
    val updateLock = new AnyRef

  
    def update() = updateLock.synchronized {
      resetSequenceNumberIfReindex()
      if (!indexingInProgress){
        indexingInProgress = true 

        var done: Boolean = false
        while(!done){
          val batch = Await.result(eliza.getThreadContentForIndexing(sequenceNumber.value, loadBatchSize), 60 seconds)
          val indexables = batch.map{ threadContent =>
            new MessageContentIndexable(
              data = threadContent,
              id = threadContent.id,
              sequenceNumber = threadContent.seq
            )
          }
          indexDocuments(indexables.iterator, commitBatchSize)
          if (batch.length<=1) done=true
        }
        indexingInProgress = false
      }
    }

  }




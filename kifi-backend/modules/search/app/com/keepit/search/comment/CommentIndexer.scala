package com.keepit.search.comment

import com.keepit.common.db._
import com.keepit.common.healthcheck.Healthcheck.INTERNAL
import com.keepit.common.healthcheck.{HealthcheckError, HealthcheckPlugin}
import com.keepit.common.net.Host
import com.keepit.common.net.URI
import com.keepit.model._
import com.keepit.search.graph.URIList
import com.keepit.search.Lang
import com.keepit.search.LangDetector
import com.keepit.search.index.DocUtil
import com.keepit.search.index.FieldDecoder
import com.keepit.search.index.{DefaultAnalyzer, Indexable, Indexer}
import com.keepit.search.index.Indexable.IteratorTokenStream
import com.keepit.search.index.Searcher
import com.keepit.search.line.LineField
import com.keepit.search.line.LineFieldBuilder
import com.keepit.shoebox.ShoeboxServiceClient
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.document.Field
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.store.Directory
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.Version
import java.io.StringReader
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.concurrent.future
import scala.concurrent.Promise
import scala.util.matching.Regex.Match
import com.keepit.social.BasicUser


object CommentFields {
  val commentIdField = "c_comment_ids"
  val uriField = "c_uri"
  val textField = "c_text"
  val textStemmedField = "c_text_stemmed"
  val titleField = "c_page_title"
  val titleStemmedField = "c_page_title_stemmed"
  val siteField = "mt_site"
  val siteKeywordField = "c_site_keywords"
  val participantNameField = "c_participant_name" // participants = initiator + recipients
  val participantIdField = "c_participant_id"
  val timestampField = "c_timestamp"

  def decoders() = Map(
    textField -> DocUtil.LineFieldDecoder,
    textStemmedField -> DocUtil.LineFieldDecoder,
    participantNameField -> DocUtil.LineFieldDecoder
  )
}

object CommentLookHereRemover {
  private[this] val lookHereRegex = """\[((?:\\\]|[^\]])*)\](\(x-kifi-sel:((?:\\\)|[^)])*)\))""".r

  def apply(text: String): String = {
    lookHereRegex.replaceAllIn(text, (m: Match) => m.group(1))
  }
}

class CommentIndexer(
    indexDirectory: Directory,
    indexWriterConfig: IndexWriterConfig,
    val commentStore: CommentStore,
    healthcheckPlugin: HealthcheckPlugin,
    shoeboxClient: ShoeboxServiceClient)
  extends Indexer[Comment](indexDirectory, indexWriterConfig, CommentFields.decoders) {

  private[this] val commitBatchSize = 3000
  private[this] val fetchSize = commitBatchSize

  private[this] val updateLock = new AnyRef
  private[this] var searchers = (this.getSearcher, commentStore.getSearcher)

  def getSearchers: (Searcher, Searcher) = searchers

  override def onFailure(indexable: Indexable[Comment], e: Throwable): Unit = {
    val msg = s"failed to build document for id=${indexable.id}: ${e.toString}"
    healthcheckPlugin.addError(HealthcheckError(errorMessage = Some(msg), callType = INTERNAL))
    super.onFailure(indexable, e)
  }

  def update(): Int = {
    resetSequenceNumberIfReindex()

    if (sequenceNumber.value > commentStore.sequenceNumber.value) {
      log.warn(s"commentStore is behind. restarting from the commentStore's sequence number: ${sequenceNumber} -> ${commentStore.sequenceNumber}")
      sequenceNumber = commentStore.sequenceNumber
    }

    var total = 0
    var done = false
    while (!done) {
      total += update {
        val comments = Await.result(shoeboxClient.getCommentsChanged(sequenceNumber, fetchSize), 180 seconds)
        done = comments.isEmpty
        comments
      }
    }
    total
  }

  private def update(commentsChanged: => Seq[Comment]): Int = updateLock.synchronized {
    log.info("updating Comment Index")
    try {
      val comments = commentsChanged
      commentStore.update(comments)

      val parentChanged = comments.foldLeft(Map.empty[Id[Comment], SequenceNumber]){ (m, c) =>
        m + (c.parent.getOrElse(c.id.get) -> c.seq)
      }.toSeq.sortBy(_._2)

      val cnt = successCount
      indexDocuments(parentChanged.iterator.map(buildIndexable), commitBatchSize)
      // update searchers together to get a consistent view of indexes
      searchers = (this.getSearcher, commentStore.getSearcher)
      successCount - cnt
    } catch { case e: Throwable =>
      log.error("error in Comment Index update", e)
      throw e
    }
  }

  override def reindex() {
    super.reindex()
    commentStore.reindex()
  }

  def buildIndexable(parentIdAndSequenceNumber: (Id[Comment], SequenceNumber)): CommentIndexable = {
    val (parentId, seq) = parentIdAndSequenceNumber
    val comments = commentStore.getComments(parentId)
    val recipientsFuture = shoeboxClient.getCommentRecipientIds(parentId)

    new CommentIndexable(id = parentId,
      sequenceNumber = seq,
      isDeleted = false,
      comments = comments,
      recipientsFuture = recipientsFuture)
  }

  class CommentIndexable(
    override val id: Id[Comment],
    override val sequenceNumber: SequenceNumber,
    override val isDeleted: Boolean,
    val comments: Seq[Comment],
    val recipientsFuture: Future[Seq[Id[User]]]
  ) extends Indexable[Comment] with LineFieldBuilder {

    import CommentFields._

    override def buildDocument = {
      val doc = super.buildDocument

      comments.find(_.id.get == id).map{ parent =>
        val preferedLang = Lang("en")

        val normUriFuture = shoeboxClient.getNormalizedURI(parent.uriId)
        val usersFutureFuture = recipientsFuture.map{ recipientIds =>
          val participants = (recipientIds.toSet + parent.userId)
          shoeboxClient.getBasicUsers(participants.toSeq)
        }

        val titleText = parent.pageTitle
        val titleLang = LangDetector.detect(titleText, preferedLang)
        val titleAnalyzer = DefaultAnalyzer.forIndexing(titleLang)
        val titleAnalyzerWithStemmer = DefaultAnalyzer.forIndexingWithStemmer(titleLang)

        val pageTitle = buildTextField(titleField, titleText, titleAnalyzer)
        doc.add(pageTitle)

        val pageTitleStemmed = buildTextField(titleStemmedField, titleText, titleAnalyzerWithStemmer)
        doc.add(pageTitleStemmed)

        // use createdAt of the most recent comment as the time stamp of this thread
        val timeStamp = comments.map(_.createdAt.getMillis).max
        doc.add(buildLongValueField(timestampField, timeStamp))

        // uri id
        doc.add(buildKeywordField(uriField, parent.uriId.id.toString))

        // index domain name
        val uri = Await.result(normUriFuture, 180 seconds)
        URI.parse(uri.url).toOption.flatMap(_.host) match {
          case Some(Host(domain @ _*)) =>
            doc.add(buildIteratorField(siteField, (1 to domain.size).iterator){ n => domain.take(n).reverse.mkString(".") })
            doc.add(buildIteratorField(siteKeywordField, (0 until domain.size).iterator)(domain))
          case _ =>
        }

        val commentIdListBytes = URIList.packLongArray(comments.map(_.id.get.id).toArray)
        val commentIds = buildBinaryDocValuesField(commentIdField, commentIdListBytes)
        doc.add(commentIds)


        val participantIds = (Await.result(recipientsFuture, 180 seconds).toSet + parent.userId)
        val usersFuture = Await.result(usersFutureFuture, 180 seconds) // is there a better way?
        val users = Await.result(usersFuture, 180 seconds)

        // user + comment text
        val commentContentList = buildCommentContentList(comments, users, Lang("en"))
        val text = buildLineField(textField, commentContentList){ (fieldName, userAndText, lang) =>
          val analyzer = DefaultAnalyzer.forIndexing(lang)
          analyzer.tokenStream(fieldName, new StringReader(userAndText._1 + "\n\n" + userAndText._2))
        }
        doc.add(text)

        // comment text stemmed
        val textStemmed = buildLineField(textStemmedField, commentContentList){ (fieldName, userAndText, lang) =>
          val analyzer = DefaultAnalyzer.forIndexingWithStemmer(lang)
          analyzer.tokenStream(fieldName, new StringReader(userAndText._2))
        }
        doc.add(textStemmed)

        // participant ids
        doc.add(buildIteratorField(participantIdField, participantIds.iterator){ id => id.toString })

        // participant names
        val participantNamelist = buildUserNameList(participantIds.toSeq, users, preferedLang)
        val participantNames = buildLineField(participantNameField, participantNamelist){ (fieldName, text, lang) =>
          val analyzer = DefaultAnalyzer.forIndexing(lang)
          analyzer.tokenStream(fieldName, new StringReader(text))
        }
        doc.add(participantNames)
      }
      doc
    }

    private def buildCommentContentList(comments: Seq[Comment], users: Map[Id[User], BasicUser], preferedLang: Lang): ArrayBuffer[(Int, (String, String), Lang)] = {
      var lineNo = 0
      var commentList = new ArrayBuffer[(Int, (String, String), Lang)]
      comments.foreach{ c =>
        val name = users.get(c.userId).map{ user => user.firstName + " " + user.lastName}.getOrElse("")
        val text = CommentLookHereRemover(c.text)
        val lang = LangDetector.detect(text, preferedLang)
        commentList += ((lineNo, (name, text), lang))
        lineNo += 1
      }
      commentList
    }

    private def buildUserNameList(userIds: Seq[Id[User]], users: Map[Id[User], BasicUser], preferedLang: Lang): ArrayBuffer[(Int, String, Lang)] = {
      var lineNo = 0
      var nameList = new ArrayBuffer[(Int, String, Lang)]
      userIds.foreach{ userId =>
        users.get(userId).map{ user =>
          val name = user.firstName + " " + user.lastName
          nameList += ((lineNo, name, Lang("en")))
          lineNo += 1
        }
      }
      nameList
    }
  }
}

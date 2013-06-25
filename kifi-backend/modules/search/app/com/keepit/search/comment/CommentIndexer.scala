package com.keepit.search.comment

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.net.Host
import com.keepit.common.net.URI
import com.keepit.model._
import com.keepit.search.Lang
import com.keepit.search.LangDetector
import com.keepit.search.index.DocUtil
import com.keepit.search.index.FieldDecoder
import com.keepit.search.index.{DefaultAnalyzer, Indexable, Indexer, IndexError}
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
import com.keepit.common.social.BasicUser
import com.keepit.search.graph.URIList


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

  def decoders() = Map.empty[String, FieldDecoder]
}

class CommentIndexer(
    indexDirectory: Directory,
    indexWriterConfig: IndexWriterConfig,
    val commentStore: CommentStore,
    shoeboxClient: ShoeboxServiceClient)
  extends Indexer[Comment](indexDirectory, indexWriterConfig, CommentFields.decoders) {

  private[this] val commitBatchSize = 3000
  private[this] val fetchSize = commitBatchSize

  private[this] val updateLock = new AnyRef
  private[this] var searchers = (this.getSearcher, commentStore.getSearcher)

  def getSearchers: (Searcher, Searcher) = searchers

  private def commitCallback(commitBatch: Seq[(Indexable[Comment], Option[IndexError])]) = {
    var cnt = 0
    commitBatch.foreach{ case (indexable, indexError) =>
      indexError match {
        case Some(error) =>
          log.error("indexing failed for user=%s error=%s".format(indexable.id, error.msg))
        case None =>
          cnt += 1
      }
    }
    cnt
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
    log.info("updating URIGraph")
    try {
      val comments = commentsChanged
      commentStore.update(comments)

      val parentChanged = comments.foldLeft(Map.empty[Id[Comment], SequenceNumber]){ (m, c) =>
        m + (c.parent.getOrElse(c.id.get) -> c.seq)
      }.toSeq.sortBy(_._2)

      var cnt = 0
      indexDocuments(parentChanged.iterator.map(buildIndexable), commitBatchSize){ commitBatch =>
        cnt += commitCallback(commitBatch)
      }
      // update searchers together to get a consistent view of indexes
      searchers = (this.getSearcher, commentStore.getSearcher)
      cnt
    } catch { case e: Throwable =>
      log.error("error in URIGraph update", e)
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

        val pageTitle = buildTextField(CommentFields.titleField, titleText, titleAnalyzer)
        doc.add(pageTitle)

        val pageTitleStemmed = buildTextField(CommentFields.titleStemmedField, titleText, titleAnalyzerWithStemmer)
        doc.add(pageTitle)

        // use createdAt of the most recent comment as the time stamp of this thread
        val timeStamp = comments.map(_.createdAt.getMillis).max
        doc.add(buildLongValueField(CommentFields.timestampField, timeStamp))

        // uri id
        doc.add(buildKeywordField(CommentFields.uriField, parent.uriId.id.toString))

        // index domain name
        val uri = Await.result(normUriFuture, 180 seconds)
        URI.parse(uri.url).toOption.flatMap(_.host) match {
          case Some(Host(domain @ _*)) =>
            doc.add(buildIteratorField(CommentFields.siteField, (1 to domain.size).iterator){ n => domain.take(n).reverse.mkString(".") })
            doc.add(buildIteratorField(CommentFields.siteKeywordField, (0 until domain.size).iterator)(domain))
          case _ =>
        }

        val ccommentIdListBytes = URIList.packLongArray(comments.map(_.id.get.id).toArray)
        val commentIdField = buildBinaryDocValuesField(CommentFields.commentIdField, ccommentIdListBytes)
        doc.add(commentIdField)


        val participantIds = (Await.result(recipientsFuture, 180 seconds).toSet + parent.userId)
        val usersFuture = Await.result(usersFutureFuture, 180 seconds) // is there a better way?
        val users = Await.result(usersFuture, 180 seconds)

        // user + comment text
        val commentContentList = buildCommentTextList(comments, users, Lang("en"))
        val text = buildLineField(CommentFields.textField, commentContentList){ (fieldName, userAndText, lang) =>
          val analyzer = DefaultAnalyzer.forIndexing(lang)
          analyzer.tokenStream(fieldName, new StringReader(userAndText._1 + "\n\n" + userAndText._2))
        }
        doc.add(text)

        // comment text stemmed
        val textStemmed = buildLineField(CommentFields.textStemmedField, commentContentList){ (fieldName, userAndText, lang) =>
          val analyzer = DefaultAnalyzer.forIndexingWithStemmer(lang)
          analyzer.tokenStream(fieldName, new StringReader(userAndText._2))
        }
        doc.add(textStemmed)

        // participant ids
        doc.add(buildIteratorField(CommentFields.participantIdField, participantIds.iterator){ id => id.toString })

        // participant names
        val participantNamelist = buildUserNameList(participantIds.toSeq, users, preferedLang)
        val participantNames = buildLineField(CommentFields.participantNameField, participantNamelist){ (fieldName, text, lang) =>
          val analyzer = DefaultAnalyzer.forIndexing(lang)
          analyzer.tokenStream(fieldName, new StringReader(text))
        }
        doc.add(participantNames)
      }
      doc
    }

    private def buildCommentTextList(comments: Seq[Comment], users: Map[Id[User], BasicUser], preferedLang: Lang): ArrayBuffer[(Int, (String, String), Lang)] = {
      var lineNo = 0
      var commentList = new ArrayBuffer[(Int, (String, String), Lang)]
      comments.foreach{ c =>
        val name = users.get(c.userId).map{ user => user.firstName + " " + user.lastName}.getOrElse("")
        val text = c.text
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

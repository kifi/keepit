package com.keepit.search.graph.user

import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.common.db.SequenceNumber
import com.keepit.search.index.Indexable
import org.apache.lucene.index.IndexWriterConfig
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.search.index._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.graph.Util

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.collection.mutable.{Map => MutableMap}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future

object SearchFriendFields {
  val unfriendedList = "unfriended"
}

class SearchFriendIndexer (
  indexDirectory: IndexDirectory,
  indexWriterConfig: IndexWriterConfig,
  airbrake: AirbrakeNotifier,
  shoeboxClient: ShoeboxServiceClient
) extends Indexer[User](indexDirectory, indexWriterConfig){

  import SearchFriendIndexer._

  override val commitBatchSize = 500
  private val fetchSize = commitBatchSize

  private def getIndexables(): Seq[SearchFriendIndexable] = {
    val changed = Await.result(shoeboxClient.getSearchFriendsChanged(sequenceNumber.value, fetchSize), 5 seconds)
    val userAndSeq = changed.groupBy(_.userId).map{ case (a, b) =>
      (a, SequenceNumber(b.map{_.seq.value}.max))
    }.toSeq.sortBy(_._2)

    val unfriendList = Await.result(Future.traverse(userAndSeq.map{_._1})(u => shoeboxClient.getUnfriends(u)), 5 seconds)

    (userAndSeq zip unfriendList).map{ case ((u, seq), unfriends) =>
      new SearchFriendIndexable(u, seq, unfriends.isEmpty, unfriends.toSeq)
    }
  }

  override def onFailure(indexable: Indexable[User], e: Throwable): Unit = {
    val msg = s"failed to build document for id=${indexable.id}: ${e.toString}"
    airbrake.notify(msg)
    super.onFailure(indexable, e)
  }

  def update(): Int = updateLock.synchronized{
    resetSequenceNumberIfReindex()

    var total = 0
    var done = false
    while (!done) {
      total += doUpdate("SearchFriendIndex") {
        val indexables = getIndexables()
        done = indexables.isEmpty
        indexables.toIterator
      }
    }
    total
  }
}

object SearchFriendIndexer {
  import SearchFriendFields._

  class SearchFriendIndexable(
    override val id: Id[User],
    override val sequenceNumber: SequenceNumber,
    override val isDeleted: Boolean,
    val unfriended: Seq[Id[User]]) extends Indexable[User] {

    override def buildDocument = {
      val doc = super.buildDocument
      val bytes = Util.packLongArray(unfriended.map { _.id }.toArray)
      val unfriendedField = buildBinaryDocValuesField(unfriendedList, bytes)
      doc.add(unfriendedField)
      doc
    }
  }
}

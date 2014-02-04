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



object UserGraphFields {
  val friendsList = "friends"
}

class UserGraphIndexer(
  indexDirectory: IndexDirectory,
  indexWriterConfig: IndexWriterConfig,
  airbrake: AirbrakeNotifier,
  shoeboxClient: ShoeboxServiceClient
) extends Indexer[User](indexDirectory, indexWriterConfig){

  import UserGraphIndexer._

  override val commitBatchSize = 500
  private val fetchSize = commitBatchSize/2              // one userConnection's seqNum corresponds to two userGraphIndexable's seqNum. Divide by 2 to make sure these two indexables are committed together.

  private def getIndexables(): Seq[UserGraphIndexable] = {
    // get userConn changed
    val conns = Await.result(shoeboxClient.getUserConnectionsChanged(sequenceNumber.value, fetchSize), 30 seconds)

    // find users affected, order by seqNum
    val m = MutableMap.empty[Id[User], Long]
    conns.foreach{ c =>
      val (u1, u2, seq) = (c.user1, c.user2, c.seq.value)
      m(u1) = m.getOrElse(u1, SequenceNumber.MinValue.value) max seq
      m(u2) = m.getOrElse(u2, SequenceNumber.MinValue.value) max seq
    }

    val userAndSeq = m.toArray.sortBy(_._2).toList

    // friend list
    val friendsListsFuture = Future.traverse(userAndSeq.map(_._1))(u => shoeboxClient.getFriends(u))
    val friendsLists = Await.result(friendsListsFuture, 30 seconds)

    // convert to indexable
    (userAndSeq zip friendsLists) map { case ((user, seq), friends) =>
      new UserGraphIndexable(user, SequenceNumber(seq), friends.isEmpty, friends.toSeq)
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
      total += doUpdate("UserGraphIndex") {
        val indexables = getIndexables()
        done = indexables.isEmpty
        indexables.toIterator
      }
    }
    total
  }

}

object UserGraphIndexer {
  import UserGraphFields._

  class UserGraphIndexable(
    override val id: Id[User],
    override val sequenceNumber: SequenceNumber,
    override val isDeleted: Boolean,
    val friends: Seq[Id[User]]
  ) extends Indexable[User] {

    override def buildDocument = {
      val doc = super.buildDocument
      val bytes = Util.packLongArray(friends.map{_.id}.toArray)
      val friendsField = buildBinaryDocValuesField(friendsList, bytes)
      doc.add(friendsField)
      doc
    }
  }
}

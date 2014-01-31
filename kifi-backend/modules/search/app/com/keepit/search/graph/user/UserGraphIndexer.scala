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



object UserGraphFields {
  val friendsList = "friends"
  def decoders() = Map
}

class UserGraphIndexer(
  indexDirectory: IndexDirectory,
  indexWriterConfig: IndexWriterConfig,
  airbrake: AirbrakeNotifier,
  shoeboxClient: ShoeboxServiceClient
) extends Indexer[User](indexDirectory, indexWriterConfig){

  import UserGraphIndexer._

  override val commitBatchSize = 500
  private val fetchSize = commitBatchSize

  private def getIndexables(): Seq[UserGraphIndexable] = {
    // get userConn changed since seqNum

    // find users affected

    // for each user, find current friend list

    // conver to indexable

    null
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

  // for tmp test only
  def update(indexables: Seq[UserGraphIndexable]): Int = updateLock.synchronized{
    resetSequenceNumberIfReindex()
    doUpdate("UserGraphIndex")(indexables.toIterator)
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

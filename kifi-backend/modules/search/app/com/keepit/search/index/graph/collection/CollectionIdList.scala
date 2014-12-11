package com.keepit.search.index.graph.collection

import com.keepit.common.db.Id
import com.keepit.model.{ Hashtag, Collection }
import org.apache.lucene.store.InputStreamDataInput
import org.apache.lucene.store.OutputStreamDataOutput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import com.keepit.search.index.graph.URIGraphUnsupportedVersionException
import com.keepit.search.index.graph.Util

trait CollectionIdList {
  val version: Int
  def size: Int
  def ids: Array[Long]
}

class SortedCollections(collections: Seq[(Id[Collection], Hashtag)]) {
  def toSeq: Seq[(Id[Collection], Hashtag)] = collections
}

object CollectionIdList {

  val currentVersion = 1

  def apply(bytes: Array[Byte]): CollectionIdList = apply(bytes, 0, bytes.length)

  def apply(bytes: Array[Byte], offset: Int, length: Int): CollectionIdList = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes, offset, length))
    val version = in.readByte()
    version match {
      case 1 => new CollectionIdListV1(in)
      case _ => throw new URIGraphUnsupportedVersionException("CollectionIdlist version=%d".format(version))
    }
  }

  def empty = new CollectionIdList {
    override val version: Int = currentVersion
    override def size: Int = 0
    override def ids: Array[Long] = Array.empty[Long]
  }

  def sortCollections(collections: Seq[(Id[Collection], Hashtag)]): SortedCollections = new SortedCollections(collections.sortBy(_._1.id))

  def toByteArray(collections: Seq[(Id[Collection], Hashtag)]): Array[Byte] = toByteArrayFromSorted(collections.sortBy(_._1.id))

  def toByteArray(collections: SortedCollections): Array[Byte] = toByteArrayFromSorted(collections.toSeq)

  private def toByteArrayFromSorted(sortedCollections: Seq[(Id[Collection], Hashtag)]): Array[Byte] = {
    val size = sortedCollections.size
    val baos = new ByteArrayOutputStream(size * 4)
    val out = new OutputStreamDataOutput(baos)

    // version
    out.writeByte(1)
    // list size
    out.writeVInt(size)
    // encode list
    var current = 0L
    sortedCollections.foreach {
      case (collectionId, name) =>
        out.writeVLong(collectionId.id - current)
        current = collectionId.id
    }

    baos.flush()
    baos.toByteArray()
  }
}

private[graph] class CollectionIdListV1(in: InputStreamDataInput) extends CollectionIdList {
  override val version: Int = 1
  override def size: Int = listSize
  override def ids: Array[Long] = idList

  private[this] val listSize = in.readVInt()
  private[this] lazy val idList: Array[Long] = Util.readList(in, listSize)
}

class CollectionIdListUnsupportedVersionException(msg: String) extends Exception(msg)


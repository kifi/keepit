package com.keepit.search.graph

import com.keepit.model.Bookmark
import org.apache.lucene.store.InputStreamDataInput
import org.apache.lucene.store.OutputStreamDataOutput
import org.apache.lucene.store.DataInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import scala.collection.mutable.ArrayBuffer

trait URIList {
  val version: Int
  def size: Int
  def ids: Array[Long]
  def createdAt: Array[Long]
}

object URIList {

  val currentVersion = 3
  def apply(bytes: Array[Byte]): URIList = apply(bytes, 0, bytes.length)

  def apply(bytes: Array[Byte], offset: Int, length: Int): URIList = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes, offset, length))
    val version = in.readByte()
    version match {
      case 2 => new URIListOld(in)
      case 3 => new URIListV3(in)
      case _ => throw new URIGraphUnknownVersionException("network payload version=%d".format(version))
    }
  }

  def empty = new URIList {
    override val version: Int = currentVersion
    override def size: Int = 0
    override def ids: Array[Long] = Array.empty[Long]
    override def createdAt: Array[Long] = Array.empty[Long]
  }

  def toByteArrays(bookmarks: Seq[Bookmark]): (Array[Byte]/*public*/, Array[Byte]/*private*/) = {
    // sort bookmarks by uriid. if there are duplicate uriIds, take most recent one
    val sortedBookmarks = bookmarks.sortWith{ (a, b) =>
      (a.uriId.id < b.uriId.id) || (a.uriId.id == b.uriId.id && a.createdAt.getMillis > b.createdAt.getMillis)
    }
    val privateBookmarks = new ArrayBuffer[Bookmark]
    val publicBookmarks = new ArrayBuffer[Bookmark]

    sortedBookmarks.headOption match {
      case Some(firstBookmark) =>
        var prevUriId = firstBookmark.uriId
        if (firstBookmark.isPrivate) privateBookmarks += firstBookmark
        else publicBookmarks += firstBookmark

        sortedBookmarks.tail.foreach{ b =>
          if (b.uriId != prevUriId) {
            if (b.isPrivate) privateBookmarks += b
            else publicBookmarks += b
            prevUriId = b.uriId
          }
        }
      case None =>
    }
    (toByteArray(publicBookmarks), toByteArray(privateBookmarks))
  }

  private def toByteArray(sortedBookmarks: Seq[Bookmark]): Array[Byte] = {
    val size = sortedBookmarks.size
    val baos = new ByteArrayOutputStream(size * 4)
    val out = new OutputStreamDataOutput(baos)

    // version
    out.writeByte(3)
    // list size
    out.writeVInt(size)
    // encode list
    var current = 0L
    sortedBookmarks.foreach{ b =>
      out.writeVLong(b.uriId.id - current)
      current = b.uriId.id
    }
    // encode createAt
    sortedBookmarks.foreach{ b => out.writeVLong(b.createdAt.getMillis / TIME_UNIT) }

    baos.flush()
    baos.toByteArray()
  }

  val TIME_UNIT = 1000L * 60L // minute
  val UNIT_PER_HOUR = (1000L * 60L * 60L).toDouble / TIME_UNIT.toDouble

  def unitToMillis(units: Long) = units * TIME_UNIT

  def readList(in: InputStreamDataInput, length: Int): Array[Long] = {
    val arr = new Array[Long](length)
    var current = 0L;
    var i = 0
    while (i < length) {
      val id = current + in.readVLong
      arr(i) = id
      current = id
      i += 1
    }
    arr
  }

  def readRawList(in: InputStreamDataInput, length: Int): Array[Long] = {
    val arr = new Array[Long](length)
    var i = 0
    while (i < length) {
      arr(i) = in.readVLong
      i += 1
    }
    arr
  }
}

private[graph] trait URIListLazyLoading {
  protected def loadListAfter(after: Any, length: Int, in: InputStreamDataInput) = {
    if (after == null) throw new IllegalStateException("loading error")
    URIList.readList(in, length)
  }

  protected def loadRawListAfter(after: Any, length: Int, in: InputStreamDataInput) = {
    if (after == null) throw new IllegalStateException("loading error")
    URIList.readRawList(in, length)
  }
}

private[graph] class URIListV3(in: InputStreamDataInput) extends URIList with URIListLazyLoading {

  override val version: Int = 3
  override def size: Int = listSize
  override def ids: Array[Long] = idList
  override def createdAt: Array[Long] = createdAtList

  private[this] val listSize = in.readVInt()
  private[this] val idList: Array[Long] = URIList.readList(in, listSize)
  private[this] lazy val createdAtList: Array[Long] = loadRawListAfter(idList, listSize, in)
}

private[graph] class URIListOld(in: InputStreamDataInput) extends URIList with URIListLazyLoading {

  override val version: Int = 2
  override def size: Int = publicListSize
  override def ids: Array[Long] = publicList
  override def createdAt: Array[Long] = publicCreatedAt

  def getPrivateURIList: URIList = {
    new URIList {
      override val version: Int = 2
      override def size = privateListSize
      override def ids = privateList
      override def createdAt = privateCreatedAt
    }
  }

  val publicListSize = in.readVInt()
  val privateListSize = in.readVInt()

  val publicList: Array[Long] = URIList.readList(in, publicListSize)
  lazy val privateList = loadListAfter(publicList, privateListSize, in)
  lazy val publicCreatedAt = loadRawListAfter(privateList, publicListSize, in)
  lazy val privateCreatedAt = loadRawListAfter(publicCreatedAt, privateListSize, in)
}

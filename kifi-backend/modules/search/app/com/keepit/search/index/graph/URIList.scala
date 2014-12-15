package com.keepit.search.index.graph

import com.keepit.model.{ KeepUriAndTime, NormalizedURI, Keep }
import org.apache.lucene.store.InputStreamDataInput
import org.apache.lucene.store.OutputStreamDataOutput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import scala.collection.mutable.ArrayBuffer
import com.keepit.common.db.Id

trait URIList {
  val version: Int
  def size: Int
  def ids: Array[Long]
  def createdAt: Array[Long]
}

class SortedBookmarks(bookmarks: Seq[Keep]) {
  def toSeq: Seq[Keep] = bookmarks
}

object URIList {

  val currentVersion = 3

  def apply(bytes: Array[Byte]): URIList = apply(bytes, 0, bytes.length)

  def apply(bytes: Array[Byte], offset: Int, length: Int): URIList = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes, offset, length))
    val version = in.readByte()
    version match {
      case 3 => new URIListV3(in)
      case _ => throw new URIGraphUnsupportedVersionException("URIlist version=%d".format(version))
    }
  }

  def empty = new URIList {
    override val version: Int = currentVersion
    override def size: Int = 0
    override def ids: Array[Long] = Array.empty[Long]
    override def createdAt: Array[Long] = Array.empty[Long]
  }

  def sortBookmarks(bookmarks: Seq[Keep]): (SortedBookmarks /*public*/ , SortedBookmarks /*private*/ ) = {
    // sort bookmarks by uriid. if there are duplicate uriIds, take most recent one
    val sortedBookmarks = bookmarks.sortWith { (a, b) =>
      (a.uriId.id < b.uriId.id) || (a.uriId.id == b.uriId.id && a.createdAt.getMillis > b.createdAt.getMillis)
    }
    val privateBookmarks = new ArrayBuffer[Keep]
    val publicBookmarks = new ArrayBuffer[Keep]

    sortedBookmarks.headOption match {
      case Some(firstBookmark) =>
        if (firstBookmark.isPrivate) privateBookmarks += firstBookmark
        else publicBookmarks += firstBookmark
        var prevUriId = firstBookmark.uriId

        sortedBookmarks.tail.foreach { b =>
          if (b.uriId != prevUriId) {
            if (b.isPrivate) privateBookmarks += b
            else publicBookmarks += b
            prevUriId = b.uriId
          }
        }
      case None =>
    }
    (new SortedBookmarks(publicBookmarks), new SortedBookmarks(privateBookmarks))
  }

  def toByteArray(uris: Seq[KeepUriAndTime]): Array[Byte] = {
    // sort bookmarks by uriid. if there are duplicate uriIds, take most recent one
    val sortedBookmarks = uris.sortWith { (a, b) =>
      (a.uriId.id < b.uriId.id) || (a.uriId.id == b.uriId.id && a.createdAt.getMillis > b.createdAt.getMillis)
    }
    val allBookmarks = new ArrayBuffer[KeepUriAndTime]

    sortedBookmarks.headOption match {
      case Some(firstBookmark) =>
        allBookmarks += firstBookmark
        var prevUriId = firstBookmark.uriId

        sortedBookmarks.tail.foreach { b =>
          if (b.uriId != prevUriId) {
            allBookmarks += b
            prevUriId = b.uriId
          }
        }
      case None =>
    }
    toByteArrayFromSorted(allBookmarks)
  }

  def toByteArray(sortedBookmarks: SortedBookmarks): Array[Byte] = toByteArrayFromSorted(sortedBookmarks.toSeq map { b => KeepUriAndTime(b.uriId, b.createdAt) })

  private def toByteArrayFromSorted(sortedBookmarks: Seq[KeepUriAndTime]): Array[Byte] = {
    val size = sortedBookmarks.size
    val baos = new ByteArrayOutputStream(size * 4)
    val out = new OutputStreamDataOutput(baos)

    // version
    out.writeByte(3)
    // list size
    out.writeVInt(size)
    // encode list
    var current = 0L
    sortedBookmarks.foreach { b =>
      out.writeVLong(b.uriId.id - current)
      current = b.uriId.id
    }
    // encode createAt
    sortedBookmarks.foreach { b => out.writeVLong(Util.millisToUnit(b.createdAt.getMillis)) }

    baos.flush()
    baos.toByteArray()
  }

}

private[graph] trait URIListLazyLoading {
  protected def loadListAfter(after: Any, length: Int, in: InputStreamDataInput) = {
    if (after == null) throw new IllegalStateException("loading error")
    Util.readList(in, length)
  }

  protected def loadRawListAfter(after: Any, length: Int, in: InputStreamDataInput) = {
    if (after == null) throw new IllegalStateException("loading error")
    Util.readRawList(in, length)
  }
}

private[graph] class URIListV3(in: InputStreamDataInput) extends URIList with URIListLazyLoading {

  override val version: Int = 3
  override def size: Int = listSize
  override def ids: Array[Long] = idList
  override def createdAt: Array[Long] = createdAtList

  private[this] val listSize = in.readVInt()
  private[this] lazy val idList: Array[Long] = Util.readList(in, listSize)
  private[this] lazy val createdAtList: Array[Long] = loadRawListAfter(idList, listSize, in)
}

class URIGraphUnsupportedVersionException(msg: String) extends Exception(msg)

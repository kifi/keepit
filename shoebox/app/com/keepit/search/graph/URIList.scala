package com.keepit.search.graph

import com.keepit.model.Bookmark
import org.apache.lucene.store.InputStreamDataInput
import org.apache.lucene.store.OutputStreamDataOutput
import org.apache.lucene.store.DataInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import scala.collection.mutable.ArrayBuffer

object URIList {
  def toByteArray(bookmarks: Seq[Bookmark]) = {
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
    val baos = new ByteArrayOutputStream(publicBookmarks.size * 4)
    val out = new OutputStreamDataOutput(baos)
    // version
    out.writeByte(2)
    // public list size and private list size
    out.writeVInt(publicBookmarks.size)
    out.writeVInt(privateBookmarks.size)

    // encode public list
    var current = 0L
    publicBookmarks.foreach{ b =>
      out.writeVLong(b.uriId.id - current)
      current = b.uriId.id
    }
    // encode private list
    current = 0L
    privateBookmarks.foreach{ b =>
      out.writeVLong(b.uriId.id - current)
      current = b.uriId.id
    }
    // encode createAt (public)
    publicBookmarks.foreach{ b => out.writeVLong(b.createdAt.getMillis / TIME_UNIT) }
    // encode createAt (private)
    privateBookmarks.foreach{ b => out.writeVLong(b.createdAt.getMillis / TIME_UNIT) }

    baos.flush()
    baos.toByteArray()
  }

  val TIME_UNIT = 1000L * 60L // minute
  val UNIT_PER_HOUR = (1000L * 60L * 60L).toDouble / TIME_UNIT.toDouble

  def unitToMillis(units: Long) = units * TIME_UNIT
}

class URIList(bytes: Array[Byte], offset: Int, length: Int) {
  def this(bytes: Array[Byte]) = this(bytes, 0, bytes.length)
  
  val in = new InputStreamDataInput(new ByteArrayInputStream(bytes, offset, length))
  val version = {
    val version = in.readByte()
    version match {
      case 1 => version
      case 2 => version
      case _ => throw new URIGraphUnknownVersionException("network payload version=%d".format(version))
    }
  }

  val publicListSize = in.readVInt()
  val privateListSize = in.readVInt()

  val publicList: Array[Long] = readList(publicListSize)
  lazy val privateList = loadListAfter(publicList, privateListSize, 1)
  lazy val publicCreatedAt = loadRawListAfter(privateList, publicListSize, 2)
  lazy val privateCreatedAt = loadRawListAfter(publicCreatedAt, privateListSize, 2)

  private def loadListAfter(after: Any, length: Int, minVersion: Int) = {
    if (version < minVersion) new Array[Long](length)
    else readList(length)
  }
  private def loadRawListAfter(after: Any, length: Int, minVersion: Int) = {
    if (version < minVersion) new Array[Long](length)
    else readRawList(length)
  }

  private def readList(length: Int) = {
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

  private def readRawList(length: Int) = {
    val arr = new Array[Long](length)
    var i = 0
    while (i < length) {
      arr(i) = in.readVLong
      i += 1
    }
    arr
  }
}

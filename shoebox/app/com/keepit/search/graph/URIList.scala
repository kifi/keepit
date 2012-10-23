package com.keepit.search.graph

import com.keepit.model.Bookmark
import org.apache.lucene.store.InputStreamDataInput
import org.apache.lucene.store.OutputStreamDataOutput
import org.apache.lucene.store.DataInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object URIList {
  def toByteArray(bookmarks: Seq[Bookmark]) = {
    val (privateBookmarks, publicBookmarks) = bookmarks.partition(_.isPrivate)
    val baos = new ByteArrayOutputStream(publicBookmarks.size * 4)
    val out = new OutputStreamDataOutput(baos)
    // version
    out.writeByte(1)
    // public list size and private list size
    out.writeVInt(publicBookmarks.size)
    out.writeVInt(privateBookmarks.size)
    // encode public list
    var current = 0L
    publicBookmarks.map(_.uriId.id).sorted.foreach{ id =>
    out.writeVLong(id - current)
    current = id
    }
    // encode private list
    current = 0L
    privateBookmarks.map(_.uriId.id).sorted.foreach{ id  =>
    out.writeVLong(id - current)
    current = id
    }
    baos.flush()
    baos.toByteArray()
  }
}

class URIList(bytes: Array[Byte]) {
  val in = new InputStreamDataInput(new ByteArrayInputStream(bytes))
  val version = {
    val version = in.readByte()
      version match {
        case 1 => version
        case _ => throw new URIGraphUnknownVersionException("network payload version=%d".format(version))
      }
    }

  val publicListSize = in.readVInt()
  val privateListSize = in.readVInt()
  val publicList: Array[Long] = readList(publicListSize)
  lazy val privateList: Array[Long] = readList(privateListSize)
  
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
}
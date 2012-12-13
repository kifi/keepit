package com.keepit.search.index

import org.apache.lucene.index.IndexReader
import scala.collection.immutable.LongMap

abstract class IdMapper {
  def getId(docid: Int): Long
  def getDocId(id: Long): Option[Int]
}

object ArrayIdMapper {
  def apply(indexReader: IndexReader) = {
    val maxDoc = indexReader.maxDoc();
    val idArray = new Array[Long](maxDoc);
    val payloadBuffer = new Array[Byte](8)
    val tp = indexReader.termPositions(Indexer.idPayloadTerm);
    try {
      var idx = 0;
      while (tp.next()) {
        val doc = tp.doc();
        while (idx < doc) {
          idArray(idx) = Indexer.DELETED_ID; // fill the gap
          idx += 1
        }
        tp.nextPosition();
        tp.getPayload(payloadBuffer, 0)
        val id = bytesToLong(payloadBuffer)
        idArray(idx) = id
        idx += 1
      }
      while(idx < maxDoc) {
        idArray(idx) = Indexer.DELETED_ID; // fill the gap
        idx += 1
      }
    } finally {
      tp.close();
    }
    new ArrayIdMapper(idArray)
  }

  def bytesToLong(bytes: Array[Byte]): Long = {
    ((bytes(0) & 0xFF).toLong) |
    ((bytes(1) & 0xFF).toLong <<  8) |
    ((bytes(2) & 0xFF).toLong << 16) |
    ((bytes(3) & 0xFF).toLong << 24) |
    ((bytes(4) & 0xFF).toLong << 32) |
    ((bytes(5) & 0xFF).toLong << 40) |
    ((bytes(6) & 0xFF).toLong << 48) |
    ((bytes(7) & 0xFF).toLong << 56)
  }
}

class ArrayIdMapper(idArray: Array[Long]) extends IdMapper {
  lazy val reserveMap: Map[Long, Int] = idArray.zipWithIndex.foldLeft(LongMap.empty[Int]){ (m, t) => m + t }

  def getId(docid: Int) = idArray(docid) // no range check done for performance
  def getDocId(id: Long) = reserveMap.get(id)
}

package com.keepit.search.index.graph.keep

import com.keepit.common.strings._
import com.keepit.search.index.Searcher
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.util.BytesRef
import scala.collection.mutable
import scala.math.floor

class KeepLangs(searcher: Searcher) {

  // we use int arrays as int value holders in the following hash map
  // we use BytesRef instead of String. BytesRef is comparable.
  private[this] val langFreq = new mutable.HashMap[BytesRef, Array[Int]]() {
    override def default(key: BytesRef): Array[Int] = new Array[Int](1) // == Array(0)
  }

  private[this] var keepCount = 0

  def processLibraries(libIds: Set[Long]): Unit = {
    libIds.foreach { libId =>
      val term = new Term(KeepFields.libraryField, libId.toString)

      searcher.foreachReader { reader =>
        val tp = reader.termPositionsEnum(term)
        if (tp != null) {
          var doc = tp.nextDoc()
          while (doc < NO_MORE_DOCS) {
            keepCount += 1
            if (tp.freq() > 0) {
              tp.nextPosition()
              val payload = tp.getPayload()
              if (payload != null) {
                val holder = langFreq(payload)
                if (holder(0) <= 0) langFreq.put(BytesRef.deepCopyOf(payload), holder)
                holder(0) += 1
              }
            }
            doc = tp.nextDoc()
          }
        }
      }
    }
  }

  def getFrequentLangs(): Seq[(String, Int)] = {
    val threshold = floor(keepCount.toDouble * 0.05) // 5%
    langFreq.iterator.collect {
      case (lang, freq) if freq(0) > threshold =>
        (new String(lang.bytes, 0, lang.length, UTF8), freq(0))
    }.toSeq.sortBy(-_._2).take(8)
  }
}

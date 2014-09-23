package com.keepit.search.graph.keep

import com.keepit.common.db.Id
import com.keepit.common.strings._
import com.keepit.model.Library
import com.keepit.search.Searcher
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import scala.collection.mutable
import scala.math.floor

class KeepLangs(searcher: Searcher) {

  private[this] val langFreq = new mutable.HashMap[String, Int]()
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
            val payload = tp.getPayload()
            if (payload != null) {
              val lang = new String(payload.bytes, 0, payload.length, UTF8)
              langFreq.put(lang, langFreq.getOrElse(lang, 0) + 1)
            }
            doc = tp.nextDoc()
          }
        }
      }
    }
  }

  def getFrequentLangs(): Seq[(String, Int)] = {
    val threshold = floor(keepCount.toDouble * 0.05) // 5%
    langFreq.filter { case (_, freq) => freq > threshold }.toSeq.sortBy(p => -p._2).take(8)
  }
}

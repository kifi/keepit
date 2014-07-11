package com.keepit.search.line

import com.keepit.common.logging.Logging
import com.keepit.search.index.CachingIndexReader
import com.keepit.search.index.CachedIndex
import com.keepit.search.index.InvertedListBuilder
import org.apache.lucene.index.AtomicReader
import org.apache.lucene.index.Term
import scala.collection.mutable.ArrayBuffer

object LineIndexReader extends Logging {

  def apply(indexReader: AtomicReader, userDocId: Int, terms: Set[Term], numLines: Int, cachedIndexOpt: Option[CachedIndex] = None): CachingIndexReader = {
    val index = terms.foldLeft(new CachedIndex(numLines)) { (index, term) =>
      cachedIndexOpt.flatMap(_.get(term)) match {
        case Some(invertedList) =>
          index + (term.field, term.bytes, invertedList)
        case _ =>
          val tp = if (userDocId >= 0) indexReader.termPositionsEnum(term) else null
          if (tp != null && tp.advance(userDocId) == userDocId) {
            val invertedListBuilder = new InvertedListBuilder
            val freq = tp.freq()
            var i = 0
            var curDoc = -1
            val plist = new ArrayBuffer[Int]
            while (i < freq) {
              val pos = tp.nextPosition()
              val docid = pos / LineField.MAX_POSITION_PER_LINE
              if (docid != curDoc) {
                if (curDoc >= 0) invertedListBuilder.add(curDoc, plist.toArray)
                curDoc = docid
                plist.clear()
              }
              plist += (pos % LineField.MAX_POSITION_PER_LINE)
              i += 1
            }
            if (curDoc >= numLines) log.error(s"curDoc=$curDoc numLines=$numLines")
            if (curDoc >= 0) invertedListBuilder.add(curDoc, plist.toArray)
            index + (term.field, term.bytes, invertedListBuilder.build)
          } else {
            index
          }
      }
    }
    new CachingIndexReader(index)
  }

  def empty: CachingIndexReader = new CachingIndexReader(new CachedIndex(0))
}

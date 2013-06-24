package com.keepit.search.line

import com.keepit.common.logging.Logging
import com.keepit.search.index.CachingIndexReader
import com.keepit.search.index.CachedIndex
import com.keepit.search.index.InvertedList
import com.keepit.search.index.InvertedListBuilder
import com.keepit.search.index.EmptyInvertedList
import org.apache.lucene.index.AtomicReader
import org.apache.lucene.index.Term
import org.apache.lucene.util.BytesRef
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import java.util.{Map=>JMap, Iterator=>JIterator, TreeMap=>JSortedMap, TreeSet=>JSortedSet}
import scala.collection.SortedMap

object LineIndexReader extends Logging {

  def apply(indexReader: AtomicReader, userDocId: Int, terms: Set[Term], numLines: Int, cachedIndexOpt: Option[CachedIndex] = None): CachingIndexReader = {
    val cachedIndex = cachedIndexOpt.getOrElse(new CachedIndex(numLines))
    val index = terms.foldLeft(cachedIndex){ (index, term) =>
      index.get(term) match {
        case Some(invertedList) => index
        case _ =>
          val tp = if (userDocId >= 0) indexReader.termPositionsEnum(term) else null
          if (tp != null && tp.advance(userDocId) == userDocId) {
            val invertedList = new InvertedListBuilder
            val freq = tp.freq()
            var i = 0
            var curDoc = -1
            val plist = new ArrayBuffer[Int]
            while (i < freq) {
              val pos = tp.nextPosition()
              val docid = pos / LineField.MAX_POSITION_PER_LINE
              if (docid != curDoc) {
                if (curDoc >= 0) invertedList.add(curDoc, plist.toArray)
                curDoc = docid
                plist.clear()
              }
              plist += (pos % LineField.MAX_POSITION_PER_LINE)
              i += 1
            }
            if (curDoc >= numLines) log.error(s"curDoc=$curDoc numLines=$numLines")
            if (curDoc >= 0) invertedList.add(curDoc, plist.toArray)
            index + (term.field, term.bytes, invertedList.build)
          } else {
            index
          }
      }
    }
    new CachingIndexReader(index)
  }
}

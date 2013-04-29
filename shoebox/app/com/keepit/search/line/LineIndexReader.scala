package com.keepit.search.line

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

object LineIndexReader {

  def apply(indexReader: AtomicReader, userDocId: Int, terms: Set[Term], numLines: Int) = {
    val index = terms.foldLeft(new CachedIndex(numLines, numLines)){ (index, term) =>
      val field = term.field
      val text = term.bytes
      val tp = indexReader.termPositionsEnum(term)
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
        if (curDoc >= 0) invertedList.add(curDoc, plist.toArray)
        index + (field, text, invertedList.build)
      } else {
        index
      }
    }
    new CachingIndexReader(index)
  }
}

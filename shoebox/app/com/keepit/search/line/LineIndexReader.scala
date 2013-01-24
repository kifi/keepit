package com.keepit.search.line

import com.keepit.common.db.Id
import com.keepit.model.{NormalizedURI, User}
import com.keepit.search.index.DocIdRemapper
import org.apache.lucene.index.IndexReader
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.Query
import org.apache.lucene.index.Term
import scala.collection.immutable.LongMap
import scala.collection.mutable.{Map => MutableMap}
import java.util.{Map => JMap}
import java.util.Arrays
import org.apache.lucene.document.FieldSelector
import org.apache.lucene.index.TermVectorMapper
import org.apache.lucene.index.TermEnum
import org.apache.lucene.index.TermDocs
import org.apache.lucene.index.TermPositions
import scala.collection.mutable.ArrayBuffer
import com.keepit.search.index.IdMapper
import com.keepit.search.index.ArrayIdMapper
import com.keepit.search.index.CachingIndexReader
import com.keepit.search.index.InvertedList
import com.keepit.search.index.InvertedListBuilder
import com.keepit.search.index.EmptyInvertedList

object LineIndexReader {

  def apply(indexReader: IndexReader, userDocId: Int, terms: Set[Term], numLines: Int) = {
    var invertedLists = terms.foldLeft(Map.empty[Term, InvertedList]){ (invertedLists, term) =>
      val tp = indexReader.termPositions(term)
      if (tp.skipTo(userDocId) && tp.doc() == userDocId) {
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
        invertedLists + (term -> invertedList.build)
      } else {
        invertedLists + (term -> EmptyInvertedList)
      }
    }
    val reader = new CachingIndexReader(invertedLists)
    reader.numDocs = numLines
    reader.maxDoc = numLines
    reader
  }
}

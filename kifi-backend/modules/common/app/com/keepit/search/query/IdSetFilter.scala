package com.keepit.search.query

import com.keepit.search.index.WrappedSubReader
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.search.DocIdSet
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Filter
import org.apache.lucene.util.Bits
import scala.collection.mutable.ArrayBuffer

class IdSetFilter(val ids: Set[Long]) extends Filter {
  override def getDocIdSet(context: AtomicReaderContext, acceptDocs: Bits): DocIdSet = {
    context.reader match {
      case reader: WrappedSubReader =>
        val idBuf = {
          val mapper = reader.getIdMapper
          val buf = new ArrayBuffer[Int]
          ids.foreach{ id => 
            val docid = mapper.getDocId(id)
            if (docid >= 0 && (acceptDocs == null || acceptDocs.get(docid))) buf += docid
          }
          buf.sorted
        }
        
        new DocIdSet { 
          override def iterator(): DocIdSetIterator = {
            new DocIdSetIterator {
              var doc = -1
              val iter = idBuf.iterator
              
              override def docID() = doc
              override def nextDoc() = {
                doc = if (iter.hasNext) iter.next() else NO_MORE_DOCS
                doc
              }
              override def advance(target: Int) = {
                do { nextDoc() } while (doc < target)
                doc
              }
            }
          }
        }
      case _ => throw new IllegalArgumentException("the reader is not WrappedSubReader")
    }
  }
}

package com.keepit.search.engine.query

import com.keepit.search.index.{ IdMapper, WrappedSubReader }
import com.keepit.search.util.{ IntArrayBuilder, LongArraySet }
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.search.DocIdSet
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Filter
import org.apache.lucene.util.Bits
import java.util.Arrays

class IdSetFilter(val ids: LongArraySet) extends Filter {
  override def getDocIdSet(context: AtomicReaderContext, acceptDocs: Bits): DocIdSet = {
    context.reader match {
      case reader: WrappedSubReader => getDocIdSet(reader.getIdMapper, acceptDocs)
      case _ => throw new IllegalArgumentException("the reader is not WrappedSubReader")
    }
  }

  def getDocIdSet(mapper: IdMapper, acceptDocs: Bits): DocIdSet = {
    val docIdArray = {
      val builder = new IntArrayBuilder
      ids.foreachLong { id =>
        val docid = mapper.getDocId(id)
        if (docid >= 0 && (acceptDocs == null || acceptDocs.get(docid))) builder += docid
      }
      val array = builder.toArray
      Arrays.sort(array)
      array
    }

    new DocIdSet {
      override def iterator(): DocIdSetIterator = {
        new DocIdSetIterator {
          private[this] var doc = -1
          private[this] var idx = 0
          private[this] val docIds = docIdArray

          override def docID() = doc
          override def nextDoc() = {
            if (idx < docIds.length) {
              doc = docIds(idx)
              idx += 1
            } else {
              doc = NO_MORE_DOCS
            }
            doc
          }
          override def advance(target: Int) = {
            do { nextDoc() } while (doc < target)
            doc
          }
          override def cost(): Long = ids.size.toLong
        }
      }
    }
  }
}

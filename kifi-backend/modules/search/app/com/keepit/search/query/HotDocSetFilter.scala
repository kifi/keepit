package com.keepit.search.query

import com.keepit.search.ResultClickBoosts
import com.keepit.search.index.WrappedSubReader
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.search.DocIdSet
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Filter
import org.apache.lucene.util.Bits
import scala.collection.mutable.ArrayBuffer

class HotDocSetFilter extends Filter {
  private[this] var ids: collection.Set[Long] = Set()
  private[this] var boosts: ResultClickBoosts = null

  def setHotDocs(hotDocs: collection.Set[Long]): Unit = { ids = hotDocs }
  def setClickBoosts(clickBoosts: ResultClickBoosts): Unit = { boosts = clickBoosts }

  override def getDocIdSet(context: AtomicReaderContext, acceptDocs: Bits): DocIdSet = {
    context.reader match {
      case reader: WrappedSubReader =>
        val docidBuf = {
          val mapper = reader.getIdMapper
          val buf = new ArrayBuffer[Int]
          ids.foreach{ id =>
            val docid = mapper.getDocId(id)
            if (docid >= 0 && (acceptDocs == null || acceptDocs.get(docid))) buf += docid
          }
          buf.sorted
        }

        new DocIdSet {

          override def iterator(): DocIdSetIterator = throw new UnsupportedOperationException

          override def bits(): Bits = new Bits {
            private[this] val docidSet = docidBuf.toSet
            private[this] val mapper = reader.getIdMapper
            private[this] val clickBoosts = boosts

            override def get(doc: Int): Boolean = (docidSet.contains(doc) || clickBoosts(mapper.getId(doc)) > 1.0f)
            override def length(): Int = reader.maxDoc
          }
        }
      case _ => throw new IllegalArgumentException("the reader is not WrappedSubReader")
    }
  }
}

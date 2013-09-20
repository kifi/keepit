package com.keepit.search.query

import com.keepit.search.ResultClickBoosts
import com.keepit.search.index.WrappedSubReader
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.search.DocIdSet
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.Filter
import org.apache.lucene.util.Bits
import scala.collection.mutable.ArrayBuffer
import com.keepit.search.index.IdMapper

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
          override def bits(): Bits = new HotDocSet(docidBuf.toSet, boosts, reader.getIdMapper)
        }

      case _ => throw new IllegalArgumentException("the reader is not WrappedSubReader")
    }
  }
}

class HotDocSet(docids: Set[Int], clickBoosts: ResultClickBoosts, mapper: IdMapper) extends Bits {
  override def get(doc: Int): Boolean = (docids.contains(doc) || clickBoosts(mapper.getId(doc)) > 1.0f)
  override def length(): Int = mapper.maxDoc

  def explain(doc: Int): Explanation = {
    new Explanation(if (get(doc)) 1.0f else 0.0f, s"hot(id-match=${docids.contains(doc)}, boosted=${clickBoosts(mapper.getId(doc)) > 1.0f})")
  }
}


package com.keepit.search.query

import com.keepit.model.BrowsingHistory
import com.keepit.search.ResultClickBoosts
import com.keepit.search.index.WrappedSubReader
import com.keepit.search.index.IdMapper
import com.keepit.search.MultiHashFilter
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.search.DocIdSet
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.Filter
import org.apache.lucene.util.Bits
import scala.collection.mutable.ArrayBuffer

class HotDocSetFilter extends Filter {
  private[this] var browsingFilter: MultiHashFilter[BrowsingHistory] = null
  private[this] var boosts: ResultClickBoosts = null

  def set(browsingHistoryFilter: MultiHashFilter[BrowsingHistory], clickBoosts: ResultClickBoosts): Unit = {
    browsingFilter = browsingHistoryFilter
    boosts = clickBoosts
  }

  override def getDocIdSet(context: AtomicReaderContext, acceptDocs: Bits): DocIdSet = {
    context.reader match {
      case reader: WrappedSubReader =>
        new DocIdSet {
          override def iterator(): DocIdSetIterator = throw new UnsupportedOperationException
          override def bits(): Bits = new HotDocSet(browsingFilter, boosts, reader.getIdMapper)
        }

      case _ => throw new IllegalArgumentException("the reader is not WrappedSubReader")
    }
  }
}

class HotDocSet(browsingFilter: MultiHashFilter[BrowsingHistory], clickBoosts: ResultClickBoosts, mapper: IdMapper) extends Bits {
  override def get(doc: Int): Boolean = {
    val id = mapper.getId(doc)
    (browsingFilter.mayContain(id, 2) || clickBoosts(id) > 1.0f)
  }
  override def length(): Int = mapper.maxDoc

  def explain(doc: Int): Explanation = {
    val id = mapper.getId(doc)
    new Explanation(if (get(doc)) 1.0f else 0.0f, s"hot(browsing=${browsingFilter.mayContain(id, 2)}, boosted=${clickBoosts(id) > 1.0f})")
  }
}


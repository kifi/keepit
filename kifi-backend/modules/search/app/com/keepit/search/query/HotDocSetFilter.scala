package com.keepit.search.query

import com.keepit.search.{BrowsedURI, MultiHashFilter, ResultClickBoosts}
import com.keepit.search.index.WrappedSubReader
import com.keepit.search.index.IdMapper
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.search.DocIdSet
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.Filter
import org.apache.lucene.util.Bits

class HotDocSetFilter extends Filter {
  private[this] var browsingFilter: MultiHashFilter[BrowsedURI] = null
  private[this] var boosts: ResultClickBoosts = null

  def set(browsingHistoryFilter: MultiHashFilter[BrowsedURI], clickBoosts: ResultClickBoosts): Unit = {
    browsingFilter = browsingHistoryFilter
    boosts = clickBoosts
  }

  override def getDocIdSet(context: AtomicReaderContext, acceptDocs: Bits): DocIdSet = {
    if (browsingFilter == null || boosts == null) throw new IllegalStateException("missing browsing history and result click boosts")

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

class HotDocSet(browsingFilter: MultiHashFilter[BrowsedURI], clickBoosts: ResultClickBoosts, mapper: IdMapper) extends Bits {
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


package com.keepit.search.query

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.MultiHashFilter
import com.keepit.search.tracker.ResultClickBoosts
import com.keepit.search.tracker.BrowsedURI
import com.keepit.search.index.WrappedSubReader
import com.keepit.search.index.IdMapper
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.search.DocIdSet
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.Filter
import org.apache.lucene.util.Bits
import scala.concurrent.Future
import scala.concurrent.duration._

class HotDocSetFilter(
  userId: Id[User],
  clickBoostsFuture: Future[ResultClickBoosts],
  browsingHistoryFuture: Future[MultiHashFilter[BrowsedURI]],
  monitoredAwait: MonitoredAwait
) extends Filter {

  private[this] lazy val browsingFilter: MultiHashFilter[BrowsedURI] =
    monitoredAwait.result(browsingHistoryFuture, 40 millisecond, s"getting browsing history for user $userId", MultiHashFilter.emptyFilter[BrowsedURI])

  private[this] lazy val boosts: ResultClickBoosts =
    monitoredAwait.result(clickBoostsFuture, 5 seconds, s"getting clickBoosts for user Id $userId")

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


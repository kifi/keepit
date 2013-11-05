package com.keepit.search.index

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.User
import com.keepit.search._
import com.keepit.search.graph.CollectionSearcherWithUser
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import scala.concurrent.duration._
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Future
import com.keepit.common.akka.MonitoredAwait

object PersonalizedSearcher {
  private val scale = 100
  def apply(userId: Id[User],
            indexReader: WrappedIndexReader,
            myUris: Set[Long],
            collectionSearcher: CollectionSearcherWithUser,
            browsingHistoryFuture: Future[MultiHashFilter[BrowsedURI]],
            clickHistoryFuture: Future[MultiHashFilter[ClickedURI]],
            svWeightMyBookMarks: Int,
            svWeightBrowsingHistory: Int,
            svWeightClickHistory: Int,
            shoeboxServiceClient: ShoeboxServiceClient,
            monitoredAwait: MonitoredAwait) = {

    new PersonalizedSearcher(
      indexReader,
      myUris,
      collectionSearcher,
      monitoredAwait.result(browsingHistoryFuture, 40 millisecond, s"getting browsing history for user $userId", MultiHashFilter.emptyFilter[BrowsedURI]),
      monitoredAwait.result(clickHistoryFuture, 40 millisecond, s"getting click history for user $userId", MultiHashFilter.emptyFilter[ClickedURI]),
      svWeightMyBookMarks * scale,
      svWeightBrowsingHistory * scale,
      svWeightClickHistory * scale)
  }

  def apply(searcher: Searcher, ids: Set[Long]) = {
    new PersonalizedSearcher(searcher.indexReader, ids, null, MultiHashFilter.emptyFilter, MultiHashFilter.emptyFilter, 1, 0, 0)
  }
}

class PersonalizedSearcher(
  override val indexReader: WrappedIndexReader,
  myUris: Set[Long],
  val collectionSearcher: CollectionSearcherWithUser,
  brosingFilterFunc: => MultiHashFilter[BrowsedURI],
  clickFilterFunc: => MultiHashFilter[ClickedURI],
  scaledWeightMyBookMarks: Int,
  scaledWeightBrowsingHistory: Int,
  scaledWeightClickHistory: Int
)
extends Searcher(indexReader) with Logging {
  import PersonalizedSearcher._

  lazy val browsingFilter: MultiHashFilter[BrowsedURI] = brosingFilterFunc
  lazy val clickFilter: MultiHashFilter[ClickedURI] = clickFilterFunc

  override protected def getSemanticVectorComposer(term: Term) = {
    val subReaders = indexReader.wrappedSubReaders
    val composer = new SemanticVectorComposer
    var i = 0
    while (i < subReaders.length) {
      val subReader = subReaders(i)
      val idMapper = subReader.getIdMapper
      val tp = subReader.termPositionsEnum(term)
      if (tp != null) {
        while (tp.nextDoc() < NO_MORE_DOCS) {
          val id = idMapper.getId(tp.docID())
          val weight = {
            if (browsingFilter.mayContain(id)){
              scaledWeightBrowsingHistory
            } else if (clickFilter.mayContain(id)) {
              scaledWeightClickHistory
            } else if (myUris.contains(id)) {
              scaledWeightMyBookMarks
            } else {
              0
            }
          }
          if (weight > 0) {
            var freq = tp.freq()
            if (freq > 0) {
              tp.nextPosition()
              val payload = tp.getPayload()
              if (payload != null) {
                composer.add(payload.bytes, payload.offset, payload.length, weight)
              } else {
                log.error(s"payload is missing: term=${term.toString}")
              }
            }
          }
        }
      }
      i += 1
    }
    composer
  }
}

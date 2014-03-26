package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.User
import com.keepit.search._
import com.keepit.search.graph.collection.CollectionSearcherWithUser
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import scala.concurrent.duration._
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Future
import com.keepit.common.akka.MonitoredAwait
import scala.concurrent.Await
import com.keepit.search.tracker.ClickedURI
import com.keepit.search.index.WrappedIndexReader
import com.keepit.search.semantic._



object PersonalizedSearcher {
  private val scale = 100
  def apply(userId: Id[User],
            indexReader: WrappedIndexReader,
            myUris: Set[Long],
            collectionSearcher: CollectionSearcherWithUser,
            clickHistoryFuture: Future[MultiHashFilter[ClickedURI]],
            svWeightMyBookMarks: Int,
            svWeightClickHistory: Int,
            monitoredAwait: MonitoredAwait,
            nonPersonalizedContextVectorFuture: Option[Future[SemanticVector]] = None,
            useNonPersonalizedContextVector: Boolean = false) = {

    new PersonalizedSearcher(
      indexReader,
      myUris,
      collectionSearcher,
      monitoredAwait.result(clickHistoryFuture, 40 millisecond, s"getting click history for user $userId", MultiHashFilter.emptyFilter[ClickedURI]),
      svWeightMyBookMarks * scale,
      svWeightClickHistory * scale,
      nonPersonalizedContextVectorFuture,
      useNonPersonalizedContextVector)
  }

  def apply(searcher: Searcher, ids: Set[Long]) = {
    new PersonalizedSearcher(searcher.indexReader, ids, null, MultiHashFilter.emptyFilter, 1, 0)
  }
}

class PersonalizedSearcher(
  override val indexReader: WrappedIndexReader,
  myUris: Set[Long],
  val collectionSearcher: CollectionSearcherWithUser,
  clickFilterFunc: => MultiHashFilter[ClickedURI],
  scaledWeightMyBookMarks: Int,
  scaledWeightClickHistory: Int,
  nonPersonalizedContextVectorFuture: Option[Future[SemanticVector]] = None,
  useNonPersonalizedContextVector: Boolean = false
)
extends Searcher(indexReader) with SearchSemanticContext with Logging {

  private[this] lazy val clickFilter: MultiHashFilter[ClickedURI] = clickFilterFunc

  override def getContextVector: SemanticVector = {
    if (useNonPersonalizedContextVector){
      Await.result(nonPersonalizedContextVectorFuture.get, 1 second)
    } else super.getContextVector
  }

  override protected def getSemanticVectorComposer(term: Term) = {
    val subReaders = indexReader.wrappedSubReaders
    val composer = new SemanticVectorComposer
    var i = 0
    var numPayloads = 0
    while (i < subReaders.length) {
      val subReader = subReaders(i)
      val idMapper = subReader.getIdMapper
      val tp = subReader.termPositionsEnum(term)
      if (tp != null) {
        val cf = clickFilter
        while (tp.nextDoc() < NO_MORE_DOCS) {
          val id = idMapper.getId(tp.docID())
          val weight = {
            if (cf.mayContain(id, 2)) {
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
                numPayloads += 1
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
    numPayloadsMap += term -> numPayloads
    composer
  }

}

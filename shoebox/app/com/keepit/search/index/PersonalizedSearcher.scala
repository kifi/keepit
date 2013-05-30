package com.keepit.search.index

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.User
import com.keepit.search.SemanticVectorComposer
import com.keepit.search.SemanticVector
import com.keepit.search.MultiHashFilter
import com.keepit.search.query.IdSetFilter
import com.keepit.search.query.QueryUtil._
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.index.SegmentReader
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.util.PriorityQueue
import scala.collection.mutable.ArrayBuffer
import com.keepit.shoebox.BrowsingHistoryTracker
import com.keepit.shoebox.ClickHistoryTracker
import scala.concurrent.duration._
import scala.concurrent.Await
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.BrowsingHistoryBuilder
import com.keepit.search.ClickHistoryBuilder
import scala.concurrent.Future
import com.keepit.model.BrowsingHistory
import com.keepit.model.ClickHistory
import com.keepit.common.akka.{MonitoredAwait, MonitoredFuture}
import scala.concurrent.ExecutionContext.Implicits.global

object PersonalizedSearcher {
  private val scale = 100
  def apply(userId: Id[User],
            indexReader: WrappedIndexReader,
            myUris: Set[Long],
            friendUris: Set[Long],
            browsingHistoryFuture: Future[MultiHashFilter[BrowsingHistory]],
            clickHistoryFuture: Future[MultiHashFilter[ClickHistory]],
            svWeightMyBookMarks: Int,
            svWeightBrowsingHistory: Int,
            svWeightClickHistory: Int,
            shoeboxServiceClient: ShoeboxServiceClient,
            monitoredAwait: MonitoredAwait,
            monitoredFuture: MonitoredFuture) = {

    val filtersFuture = for {
      browsingHistoryFilter <- monitoredFuture.recover(browsingHistoryFuture, {case ex: Throwable => MultiHashFilter.emptyFilter[BrowsingHistory]})
      clickHistoryFilter <- monitoredFuture.recover(clickHistoryFuture, {case ex: Throwable => MultiHashFilter.emptyFilter[ClickHistory]})
    } yield (browsingHistoryFilter, clickHistoryFilter)

    val (browsingHistoryFilter, clickHistoryFilter) = monitoredAwait.result(filtersFuture, 40 millisecond)

    new PersonalizedSearcher(indexReader, myUris, friendUris,
                             browsingHistoryFilter,
                             clickHistoryFilter,
                             svWeightMyBookMarks * scale, svWeightBrowsingHistory * scale, svWeightClickHistory * scale)
  }

  def apply(searcher: Searcher, ids: Set[Long]) = {
    new PersonalizedSearcher(searcher.indexReader, ids, Set.empty[Long], MultiHashFilter.emptyFilter, MultiHashFilter.emptyFilter, 1, 0, 0)
  }

  class IdSampler(sampleSize: Int, seed: Long) {
    class Pair(var hash: Int, var id: Long) {
      def set(h: Int, i: Long) = {
        hash = h
        id = i
        this
      }
    }
    private[this] var overflow: Pair = null

    private[this] val pq = new PriorityQueue[Pair](sampleSize) {
      def lessThan(a: Pair, b: Pair) : Boolean = (a.hash < b.hash || (a.hash == b.hash && a.id > b.id))
      def getIdSet() = {
        val heap = getHeapArray()
        (1 to size()).map{ i => heap(i).asInstanceOf[Pair].id }.toSet
      }
    }

    def put(id: Long) = {
      val hash = (((id + seed) * 25214903917L) & 0x7FFFFFFFL).toInt
      overflow = pq.insertWithOverflow(if (overflow == null) new Pair(hash, id) else overflow.set(hash, id))
    }

    def size(): Int = pq.size()

    def getIdSet(): Set[Long] = pq.getIdSet()
  }
}

class PersonalizedSearcher(override val indexReader: WrappedIndexReader, myUris: Set[Long], friendUris: Set[Long],
                           browsingFilter: MultiHashFilter[BrowsingHistory], clickFilter: MultiHashFilter[ClickHistory],
                           scaledWeightMyBookMarks: Int, scaledWeightBrowsingHistory: Int, scaledWeightClickHistory: Int)
extends Searcher(indexReader) with Logging {
  import PersonalizedSearcher._

  override protected def getSemanticVectorComposer(term: Term) = {
    val sampler = new IdSampler(64, term.hashCode.toLong)
    val subReaders = indexReader.wrappedSubReaders
    val composer = new SemanticVectorComposer
    var i = 0
    var cnt = 0
    val minMyCount = 3
    while (i < subReaders.length) {
      val subReader = subReaders(i)
      val idMapper = subReader.getIdMapper
      val tp = subReader.termPositionsEnum(term)
      if (tp != null) {
        while (tp.nextDoc() < NO_MORE_DOCS) {
          val id = idMapper.getId(tp.docID())
          val weight = {
            if (clickFilter.mayContain(id)) scaledWeightClickHistory
            else if (browsingFilter.mayContain(id)) scaledWeightBrowsingHistory
            else if (myUris.contains(id)) scaledWeightMyBookMarks
            else {
              if (cnt < minMyCount && friendUris.contains(id)) sampler.put(id)
              0
            }
          }
          if (weight > 0) {
            cnt += 1
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
    val samples = sampler.getIdSet
    val sampleSize = samples.size
    if (cnt < minMyCount && sampleSize > 0) {
      val weight = composer.numInputs / sampleSize
      addSampledSemanticVectors(composer, samples, term, if (weight > 0) weight else 1)
    }
    composer
  }

  private def addSampledSemanticVectors(composer: SemanticVectorComposer, samples: Set[Long], term: Term, weight: Int) {
    val filter = new IdSetFilter(samples)
    var idsToCheck = samples.size // for early stop: don't need to go through every subreader

    val subReaders = indexReader.wrappedSubReaders
    var i = 0
    while (i < subReaders.length && idsToCheck > 0) {
      val subReader = subReaders(i)
       val docIdSet = filter.getDocIdSet(subReader.getContext, subReader.getLiveDocs)
      if (docIdSet != null) {
        val tp = filteredTermPositionsEnum(subReader.termPositionsEnum(term), docIdSet)
        if (tp != null) {
          while (tp.nextDoc() < NO_MORE_DOCS) {
            idsToCheck -= 1
            if (tp.freq() > 0){
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
  }
}

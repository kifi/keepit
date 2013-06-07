package com.keepit.search.index

import com.keepit.search.SemanticVector
import com.keepit.search.SemanticVectorComposer
import com.keepit.search.query.IdSetFilter
import com.keepit.search.query.QueryUtil._
import org.apache.lucene.index.AtomicReader
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.DocsAndPositionsEnum
import org.apache.lucene.index.FilterAtomicReader.FilterDocsAndPositionsEnum
import org.apache.lucene.index.Term
import org.apache.lucene.index.SegmentReader
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.similarities.TFIDFSimilarity
import org.apache.lucene.util.Bits
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.PriorityQueue
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions._
import scala.math._


object Searcher {
  def apply(indexReader: DirectoryReader) = new Searcher(WrappedIndexReader(indexReader))
  def reopen(oldSearcher: Searcher) = new Searcher(WrappedIndexReader.reopen(oldSearcher.indexReader))

  private[search] class FallbackDocsAndPositionsEnum(primary: DocsAndPositionsEnum, secondary: DocsAndPositionsEnum) extends DocsAndPositionsEnum {
    private[this] var doc = -1
    private[this] var docPrimary = -1
    private[this] var docSecondary = -1

    override def docID(): Int = doc

    override def nextDoc(): Int = {
      doc = doc + 1
      if (docPrimary < doc) docPrimary = primary.advance(doc)
      if (docSecondary < doc) docSecondary = secondary.advance(doc)
      doc = min(docPrimary, docSecondary)
      doc
    }

    override def advance(target: Int): Int = {
      doc = if (doc < target) target else doc + 1
      if (docPrimary <= target) docPrimary = primary.advance(doc)
      if (docSecondary <= target) docSecondary = secondary.advance(doc)
      doc = min(docPrimary, docSecondary)
      doc
    }

    private def tp: DocsAndPositionsEnum = if (doc == docPrimary) primary else secondary
    override def freq(): Int = tp.freq()
    override def nextPosition(): Int = tp.nextPosition()
    override def getPayload(): BytesRef = tp.getPayload()
    override def startOffset() = -1
    override def endOffset() = -1
  }
}

class Searcher(val indexReader: WrappedIndexReader) extends IndexSearcher(indexReader) {

  def idf(term: Term) = getSimilarity.asInstanceOf[TFIDFSimilarity]idf(indexReader.docFreq(term), indexReader.maxDoc)

  private[this] var svMap = Map.empty[Term, SemanticVector]

  // search: hits are ordered by score
  def search(query: Query): Seq[Hit] = {
    val hitBuf = new ArrayBuffer[Hit]()
    doSearch(query){ (scorer, idMapper) =>
      var doc = scorer.nextDoc()
      while (doc != NO_MORE_DOCS) {
        var score = scorer.score()
        hitBuf += Hit(idMapper.getId(doc), score)
        doc = scorer.nextDoc()
      }
    }
    hitBuf.sortWith((a, b) => a.score >= b.score).toSeq
  }

  def doSearch(query: Query)(f: (Scorer, IdMapper) => Unit) {
    val rewrittenQuery = rewrite(query)
    if (rewrittenQuery != null) {
      val weight = createNormalizedWeight(rewrittenQuery)
      if(weight != null) {
        indexReader.getContext.leaves.foreach{ subReaderContext =>
          val subReader = subReaderContext.reader.asInstanceOf[WrappedSubReader]
          val scorer = weight.scorer(subReaderContext, true, false, subReader.getLiveDocs)
          if (scorer != null) {
            f(scorer, subReader.getIdMapper)
          }
        }
      }
    }
  }

  def foreachReader(f: WrappedSubReader => Unit) {
    indexReader.getContext.leaves.foreach{ subReaderContext =>
      val subReader = subReaderContext.reader.asInstanceOf[WrappedSubReader]
      f(subReader)
    }
  }

  def findDocIdAndAtomicReaderContext(id: Long): Option[(Int, AtomicReaderContext)] = {
    indexReader.getContext.leaves.foreach{ subReaderContext =>
      val subReader = subReaderContext.reader.asInstanceOf[WrappedSubReader]
      val liveDocs = subReader.getLiveDocs
      val docid = subReader.getIdMapper.getDocId(id)
      if (docid >= 0 && (liveDocs == null || liveDocs.get(docid))) return Some((docid, subReaderContext))
    }
    None
  }

  def explain(query: Query, id: Long): Explanation = {
    findDocIdAndAtomicReaderContext(id) match {
      case Some((docid, context)) =>
        val rewrittenQuery = rewrite(query)
        if (rewrittenQuery != null) {
          val weight = createNormalizedWeight(rewrittenQuery)
          weight.explain(context, docid)
        } else {
          new Explanation(0.0f, "rewrittten query is null")
        }
      case None =>
        new Explanation(0.0f, "failed to find docid")
    }
  }

  protected def getSemanticVectorComposer(term: Term) = {
    val composer = new SemanticVectorComposer
    indexReader.getContext.leaves.foreach{ subReaderContext =>
      val subReader = subReaderContext.reader.asInstanceOf[WrappedSubReader]
      val tp = subReader.termPositionsEnum(term)
      var vector = new SemanticVector(new Array[Byte](SemanticVector.arraySize))
      while (tp.nextDoc < NO_MORE_DOCS) {
        var freq = tp.freq()
        while (freq > 0) {
          freq -= 1
          tp.nextPosition()
          val payload = tp.getPayload()
          vector.set(payload.bytes, payload.offset, payload.length)
          composer.add(vector, 1)
        }
      }
    }
    composer
  }

  def getSemanticVector(term: Term) = {
    svMap.getOrElse(term, {
      val composer = getSemanticVectorComposer(term)
      val sv = {
        if (composer.numInputs > 0) composer.getSemanticVector
        else SemanticVector.vectorize(SemanticVector.getSeed(term.text))
      }
      svMap += (term -> sv)
      sv
    })
  }

  def getSemanticVector(terms: Set[Term]) = {
    val sketch = terms.foldLeft(SemanticVector.emptySketch){ (sketch, term) =>
      val composer = getSemanticVectorComposer(term)
      val termSketch = if (composer.numInputs > 0) composer.getQuasiSketch else SemanticVector.getSeed(term.text)
      SemanticVector.updateSketch(sketch, termSketch)
      sketch
    }
    SemanticVector.vectorize(sketch)
  }

  def getSemanticVectorEnum(context: AtomicReaderContext, primaryTerm: Term, secondaryTerm: Term, acceptDocs: Bits) = {
    val primary = termPositionsEnum(context, primaryTerm, acceptDocs)
    val defaultSV = getSemanticVector(primaryTerm) // use the query's sv as a default
    val secondary = getFallbackSemanticVectorEnum(context, secondaryTerm, defaultSV, acceptDocs)

    if (primary == null) secondary
    else if (secondary == null) primary
    else {
      new Searcher.FallbackDocsAndPositionsEnum(primary, secondary)
    }
  }

  private def getFallbackSemanticVectorEnum(context: AtomicReaderContext, term: Term, sv: SemanticVector, acceptDocs: Bits): DocsAndPositionsEnum = {
    val tp = termPositionsEnum(context, term, acceptDocs)
    if (tp != null) {
      val payload = new BytesRef(sv.bytes, 0, sv.bytes.length)
      new FilterDocsAndPositionsEnum(tp) {
        override def getPayload(): BytesRef = payload
      }
    } else null
  }

  /**
   * Given a term and a set of documents, we find the semantic vector for each
   * (term, document) pair.
   * Note: this may return empty map
   */
  def getSemanticVectors(term: Term, uriIds:Set[Long]): Map[Long, SemanticVector] = {

    val subReaders = indexReader.wrappedSubReaders
    var sv = Map[Long, SemanticVector]()
    var i = 0

    var idsToCheck = uriIds.size // for early stop: don't need to go through every subreader
    val filter = new IdSetFilter(uriIds)

    while (i < subReaders.length && idsToCheck > 0) {
      val subReader = subReaders(i)
      val docIdSet = filter.getDocIdSet(subReader.getContext, subReader.getLiveDocs)
      if (docIdSet != null) {
        val tp = filteredTermPositionsEnum(subReader.termPositionsEnum(term), docIdSet)
        if (tp != null) {
          val mapper = subReader.getIdMapper
          while (tp.nextDoc() < NO_MORE_DOCS) {
            idsToCheck -= 1
            val id = mapper.getId(tp.docID)
            val vector = new SemanticVector(new Array[Byte](SemanticVector.arraySize))
            if (tp.freq() > 0){
              tp.nextPosition()
              val payload = tp.getPayload()
              vector.set(payload.bytes, payload.offset, payload.length)
              sv += (id -> vector)
            }
          }
        }
      }
      i += 1
    }
    sv
  }

  def filterByTerm(uriIds: Set[Long], term: Term): Set[Long] = {
    val subReaders = indexReader.wrappedSubReaders
    var res = Set[Long]()

    var idsToCheck = uriIds.size // for early stop: don't need to go through every subreader
    val filter = new IdSetFilter(uriIds)

    var i = 0
    while (i < subReaders.length && idsToCheck > 0) {
      val subReader = subReaders(i)
      val docIdSet = filter.getDocIdSet(subReader.getContext, subReader.getLiveDocs)
      if (docIdSet != null) {
        val tp = filteredTermPositionsEnum(subReader.termPositionsEnum(term), docIdSet)
        if (tp != null) {
          val mapper = subReader.getIdMapper

          while (tp.nextDoc() < NO_MORE_DOCS) {
            idsToCheck -= 1
            res += mapper.getId(tp.docID)
          }
        }
      }
      i += 1
    }
    res
  }
  def getHitQueue(size: Int) = new HitQueue(size)
}

class MutableHit(var id: Long, var score: Float) {
  def apply(newId: Long, newScore: Float) = {
    id = newId
    score = newScore
  }
}

class HitQueue(sz: Int) extends PriorityQueue[MutableHit](sz) {
  override def lessThan(a: MutableHit, b: MutableHit) = (a.score < b.score || (a.score == b.score && a.id < b.id))

  var overflow: MutableHit = null // sorry about the null, but this is necessary to work with lucene's priority queue efficiently

  def insert(id: Long, score: Float) {
    if (overflow == null) overflow = new MutableHit(id, score)
    else overflow(id, score)

    overflow = insertWithOverflow(overflow)
  }

  // the following method is destructive. after the call HitQueue is unusable
  def toList: List[MutableHit] = {
    var res: List[MutableHit] = Nil
    var i = size()
    while (i > 0) {
      i -= 1
      res = pop() :: res
    }
    res
  }
}

case class Hit(id: Long, score: Float)


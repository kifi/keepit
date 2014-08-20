package com.keepit.search

import com.keepit.search.semantic._
import com.keepit.search.semantic.SemanticVector.Sketch
import com.keepit.search.query.QueryUtil._
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.util.Bits
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.PriorityQueue
import com.keepit.search.index._
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions._
import org.apache.lucene.search.Filter
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.Weight

object Searcher {
  def apply(indexReader: DirectoryReader) = new Searcher(WrappedIndexReader(indexReader))
  def reopen(oldSearcher: Searcher) = {
    new Searcher(WrappedIndexReader.reopen(oldSearcher.indexReader))
  }
}

class Searcher(val indexReader: WrappedIndexReader) extends IndexSearcher(indexReader) {

  private[this] var sketchMap = Map.empty[Term, Sketch]
  protected[this] var numPayloadsMap: Map[Term, Int] = Map()

  def numPayloadsUsed(term: Term): Int = numPayloadsMap.getOrElse(term, 0)

  // search: hits are ordered by score
  def searchAll(query: Query): Seq[SearcherHit] = {
    val hitBuf = new ArrayBuffer[SearcherHit]()
    search(query) { (scorer, reader) =>
      val idMapper = reader.getIdMapper
      var doc = scorer.nextDoc()
      while (doc != NO_MORE_DOCS) {
        var score = scorer.score()
        hitBuf += SearcherHit(idMapper.getId(doc), score)
        doc = scorer.nextDoc()
      }
    }
    hitBuf.sortWith((a, b) => a.score >= b.score).toSeq
  }

  def findAllIds(term: Term, buf: ArrayBuffer[Long] = new ArrayBuffer[Long]): Seq[Long] = {
    foreachReader { reader =>
      val idMapper = reader.getIdMapper
      val td = reader.termDocsEnum(term)
      var doc = td.nextDoc()
      while (doc != NO_MORE_DOCS) {
        buf += idMapper.getId(doc)
        doc = td.nextDoc()
      }
    }
    buf
  }

  def count(term: Term): Int = {
    var count = 0
    foreachReader { reader =>
      var cnt = 0
      val idMapper = reader.getIdMapper
      val td = reader.termDocsEnum(term)
      var doc = td.nextDoc()
      while (doc != NO_MORE_DOCS) {
        cnt += 1
        doc = td.nextDoc()
      }
      count += cnt
    }
    count
  }

  def createWeight(query: Query): Weight = {
    val rewrittenQuery = rewrite(query)
    if (rewrittenQuery != null) createNormalizedWeight(rewrittenQuery) else null
  }

  def search(query: Query)(f: (Scorer, WrappedSubReader) => Unit) {
    search(createWeight(query: Query))(f)
  }

  def search(weight: Weight)(f: (Scorer, WrappedSubReader) => Unit) {
    if (weight != null) {
      indexReader.getContext.leaves.foreach { subReaderContext =>
        val subReader = subReaderContext.reader.asInstanceOf[WrappedSubReader]
        if (!subReader.skip) {
          val scorer = weight.scorer(subReaderContext, true, false, subReader.getLiveDocs)
          if (scorer != null) {
            f(scorer, subReader)
          }
        }
      }
    }
  }

  def foreachReader(f: WrappedSubReader => Unit) {
    indexReader.getContext.leaves.foreach { subReaderContext =>
      val subReader = subReaderContext.reader.asInstanceOf[WrappedSubReader]
      f(subReader)
    }
  }

  def findDocIdAndAtomicReaderContext(id: Long): Option[(Int, AtomicReaderContext)] = {
    indexReader.getContext.leaves.foreach { subReaderContext =>
      val subReader = subReaderContext.reader.asInstanceOf[WrappedSubReader]
      val liveDocs = subReader.getLiveDocs
      val docid = subReader.getIdMapper.getDocId(id)
      if (docid >= 0 && (liveDocs == null || liveDocs.get(docid))) return Some((docid, subReaderContext))
    }
    None
  }

  def getDecodedDocValue[T](field: String, id: Long)(implicit decode: (Array[Byte], Int, Int) => T): Option[T] = {
    findDocIdAndAtomicReaderContext(id).flatMap {
      case (docid, context) =>
        val reader = context.reader
        var docValues = reader.getBinaryDocValues(field)
        if (docValues != null) {
          var ref = new BytesRef()
          docValues.get(docid, ref)
          Some(decode(ref.bytes, ref.offset, ref.length))
        } else {
          None
        }
    }
  }

  def getLongDocValue(field: String, id: Long): Option[Long] = {
    findDocIdAndAtomicReaderContext(id).flatMap {
      case (docid, context) =>
        val reader = context.reader
        var docValues = reader.getNumericDocValues(field)
        if (docValues != null) {
          Some(docValues.get(docid))
        } else {
          None
        }
    }
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
    indexReader.getContext.leaves.foreach { subReaderContext =>
      val subReader = subReaderContext.reader.asInstanceOf[WrappedSubReader]
      val tp = subReader.termPositionsEnum(term)
      if (tp != null) {
        while (tp.nextDoc < NO_MORE_DOCS) {
          var freq = tp.freq()
          numPayloadsMap += term -> freq
          while (freq > 0) {
            freq -= 1
            tp.nextPosition()
            val payload = tp.getPayload()
            composer.add(payload.bytes, payload.offset, payload.length, 1)
          }
        }
      }
    }
    composer
  }

  def getSemanticVectorSketch(term: Term): Sketch = {
    sketchMap.getOrElse(term, {
      val composer = getSemanticVectorComposer(term)
      val sketch = if (composer.numInputs > 0) composer.getQuasiSketch else SemanticVector.getSeed(term.text)
      sketchMap += (term -> sketch)
      sketch
    })
  }

  def getSemanticVectorSketch(terms: Set[Term]): Sketch = {
    terms.foldLeft(SemanticVector.emptySketch) { (sketch, term) =>
      SemanticVector.updateSketch(sketch, getSemanticVectorSketch(term))
      sketch
    }
  }

  def getSemanticVector(term: Term): SemanticVector = SemanticVector.vectorize(getSemanticVectorSketch(term))

  def getSemanticVector(term: Term, contextSketch: Sketch, norm: Float): SemanticVector = {
    val termSketch = getSemanticVectorSketch(term).clone()
    SemanticVector.updateSketch(termSketch, contextSketch, norm)
    SemanticVector.vectorize(termSketch)
  }

  def getSemanticVector(terms: Set[Term]): SemanticVector = SemanticVector.vectorize(getSemanticVectorSketch(terms))

  def getSemanticVectorEnum(context: AtomicReaderContext, term: Term, acceptDocs: Bits): SemanticVectorEnum = {
    val tp = termPositionsEnum(context, term, acceptDocs)
    if (tp != null) new SemanticVectorEnum(tp) else null
  }

  def hasSemanticContext: Boolean = false
  def numOfContextTerms: Int = 0
  def addContextTerm(term: Term): Unit = throw new UnsupportedOperationException("not available. create a new searcher instance usiung withSemanticContext")
  def getContextSketch: Sketch = throw new UnsupportedOperationException("not available. create a new searcher instance with SearchSemanticContext")
  def getContextVector: SemanticVector = throw new UnsupportedOperationException("not available. create a new searcher instance using withSemanticContext")

  def withSemanticContext: Searcher = new Searcher(indexReader) with SearchSemanticContext
}

trait SearchSemanticContext extends Searcher {

  private[this] var contextTerms = Set.empty[Term]
  private[this] var contextSketch: Option[Sketch] = None
  private[this] var contextVector: Option[SemanticVector] = None

  override def hasSemanticContext: Boolean = true

  override def numOfContextTerms: Int = contextTerms.size

  override def addContextTerm(term: Term): Unit = { // SemanticVectorWeight constructor should call this
    contextSketch = None
    contextVector = None
    contextTerms += term
  }

  override def getContextSketch: Sketch = {
    contextSketch match {
      case Some(sketch) => sketch
      case None =>
        val sketch = getSemanticVectorSketch(contextTerms)
        contextSketch = Some(sketch)
        sketch
    }
  }

  override def getContextVector: SemanticVector = {
    contextVector match {
      case Some(vector) => vector
      case None =>
        val vector = SemanticVector.vectorize(getContextSketch)
        contextVector = Some(vector)
        vector
    }
  }
}

case class SearcherHit(id: Long, score: Float)


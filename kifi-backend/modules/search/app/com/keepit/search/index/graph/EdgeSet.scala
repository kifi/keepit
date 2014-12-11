package com.keepit.search.index.graph

import com.keepit.common.db.Id
import com.keepit.search.Searcher
import com.keepit.search.query.QueryUtil._
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.index.Term
import com.keepit.search.util.LongArraySet
import java.util.Arrays

trait EdgeSet[S, D] {
  val sourceId: Id[S]

  def destIdSet: Set[Id[D]]
  def destIdLongSet: Set[Long]

  def getDestDocIdSetIterator(searcher: Searcher): DocIdSetIterator
  def size: Int
  def isEmpty: Boolean = (size == 0)

  def accessor: EdgeSetAccessor[S, D] = new SimpleEdgeSetAccessor(this)

  implicit def toIterator(it: DocIdSetIterator): Iterator[Int] = {
    if (it != null) {
      new Iterator[Int] {
        var nextDocId = it.nextDoc()
        def hasNext = nextDocId < NO_MORE_DOCS
        def next = {
          val cur = nextDocId
          nextDocId = it.nextDoc()
          cur
        }
      }
    } else {
      Iterator.empty
    }
  }

  def toDocIdSetIterator(docids: Array[Int]) = {
    new DocIdSetIterator {
      private[this] var curDoc = -1
      private[this] var curIdx = -1

      def docID() = curDoc

      def nextDoc() = {
        curIdx += 1
        curDoc = if (curIdx < docids.length) docids(curIdx) else NO_MORE_DOCS
        curDoc
      }

      def advance(target: Int) = {
        while (curDoc < target) nextDoc
        curDoc
      }

      def cost(): Long = docids.length.toLong
    }
  }
}

// A set of db ids are given as the destination set. Need IdMapper to map them to Lucene DocIds. Dual of DocIdSetEdgeSet
trait DbIdSetEdgeSet[S, D] extends EdgeSet[S, D] {
  protected var cache: (Searcher, Array[Int]) = (null, null)

  protected def getDocIds(searcher: Searcher): Array[Int] = {
    cache match {
      case (curSearcher, curDocIds) if (curSearcher eq searcher) =>
        curDocIds
      case _ =>
        val mapper = searcher.indexReader.asAtomicReader.getIdMapper
        val docids = destIdSet.map { id => mapper.getDocId(id.id) }.filter { _ >= 0 }.toArray
        Arrays.sort(docids)
        cache = (searcher, docids)
        docids
    }
  }

  override def getDestDocIdSetIterator(searcher: Searcher): DocIdSetIterator = toDocIdSetIterator(getDocIds(searcher))
}

trait IdSetEdgeSet[S, D] extends DbIdSetEdgeSet[S, D] {
  override lazy val destIdLongSet: Set[Long] = destIdSet.map(_.id)
  override def size = destIdSet.size
}

trait LongSetEdgeSet[S, D] extends DbIdSetEdgeSet[S, D] {
  override lazy val destIdSet: Set[Id[D]] = new IdSetWrapper[D](destIdLongSet)
  override def size = destIdLongSet.size
}

trait LongArraySetEdgeSet[S, D] extends DbIdSetEdgeSet[S, D] {
  protected val longArraySet: LongArraySet
  override def accessor: EdgeSetAccessor[S, D] = new LongArrayBasedEdgeInfoAccessorImpl(this, longArraySet: LongArraySet)
  override def destIdLongSet = longArraySet
  override lazy val destIdSet: Set[Id[D]] = new IdSetWrapper[D](destIdLongSet)
  override def size = longArraySet.size
}

// A set of Lucene DocIds are given as the destination set. Need IdMapper to map them to DB Ids. Dual of DbIdSetEdgeSet
trait DocIdSetEdgeSet[S, D] extends EdgeSet[S, D] {
  val docids: Array[Int]
  val searcher: Searcher

  private[this] lazy val lazyDestIdLongSet: Set[Long] = {
    val mapper = searcher.indexReader.asAtomicReader.getIdMapper
    val res = new Array[Long](docids.length)
    var i = 0
    while (i < docids.length) {
      res(i) = mapper.getId(docids(i))
      i += 1
    }
    LongArraySet.from(res)
  }

  private[this] lazy val lazyDestIdSet: Set[Id[D]] = new IdSetWrapper(lazyDestIdLongSet)

  override def destIdLongSet = lazyDestIdLongSet
  override def destIdSet = lazyDestIdSet

  override def getDestDocIdSetIterator(curSearcher: Searcher): DocIdSetIterator = {
    if (curSearcher eq searcher) toDocIdSetIterator(docids)
    else throw new Exception("searcher does not match")
  }

  override def size = docids.length
}

// only the sourceId is given. Destination set is constructed by performing Lucene search.
trait LuceneBackedEdgeSet[S, D] extends EdgeSet[S, D] {
  val searcher: Searcher
  val sourceFieldName: String

  private[this] lazy val lazyDestIdLongSet: Set[Long] = {
    val mapper = searcher.indexReader.asAtomicReader.getIdMapper
    getDestDocIdSetIterator(searcher).map(docid => mapper.getId(docid)).toSet
  }

  private[this] lazy val lazyDestIdSet: Set[Id[D]] = new IdSetWrapper[D](lazyDestIdLongSet)

  override def destIdLongSet = lazyDestIdLongSet
  override def destIdSet = lazyDestIdSet

  override def size = getDestDocIdSetIterator(searcher).size
  override def isEmpty = getDestDocIdSetIterator(searcher).isEmpty

  override def getDestDocIdSetIterator(searcher: Searcher): DocIdSetIterator = {
    val td = searcher.indexReader.asAtomicReader.termDocsEnum(createSourceTerm)
    if (td != null) td else emptyDocIdSetIterator
  }

  protected def createSourceTerm: Term = new Term(sourceFieldName, sourceId.toString)
}

class IdSetWrapper[T](inner: Set[Long]) extends Set[Id[T]] {

  override def contains(elem: Id[T]): Boolean = inner.contains(elem.id)

  override def iterator: Iterator[Id[T]] = inner.iterator.map { Id[T](_) }

  override def +(elem: Id[T]): Set[Id[T]] = new IdSetWrapper[T](inner + elem.id)

  override def -(elem: Id[T]): Set[Id[T]] = new IdSetWrapper[T](inner - elem.id)

  override def size: Int = inner.size
}

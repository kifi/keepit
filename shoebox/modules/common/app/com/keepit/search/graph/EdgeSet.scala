package com.keepit.search.graph

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.search.index.IdMapper
import com.keepit.search.index.Searcher
import com.keepit.search.query.QueryUtil._
import org.apache.lucene.search.DocIdSet
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import scala.util.Sorting
import org.apache.lucene.index.Term
import com.keepit.search.util.LongArraySet
import org.joda.time.DateTime
import scala.collection.mutable.ArrayBuffer

trait EdgeSet[S,D] {
  val sourceId: Id[S]

  def destIdSet: Set[Id[D]]
  def destIdLongSet: Set[Long]

  def getDestDocIdSetIterator(searcher: Searcher): DocIdSetIterator
  def size: Int

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
        curDoc = if(curIdx < docids.length) docids(curIdx) else NO_MORE_DOCS
        curDoc
      }

      def advance(target: Int) = {
        while (curDoc < target) nextDoc
        curDoc
      }
    }
  }

  def accessor: EdgeAccessor[S, D] = new EdgeAccessor[S, D](this)

  def filterByTimeRange(start: DateTime, end: DateTime): EdgeSet[S, D] = filterByTimeRange(start.getMillis, end.getMillis)

  def filterByTimeRange(start: Long, end: Long): EdgeSet[S, D] = {
    val acc = accessor
    val filtered = destIdLongSet.filter{ id =>
      val timestamp = acc.getCreatedAt(id)
      start <= timestamp && timestamp <= end
    }.toArray
    Sorting.quickSort(filtered)
    val inheritedSourceId = sourceId
    new LongSetEdgeSet[S, D] {
      override val sourceId: Id[S] = inheritedSourceId
      override val longArraySet = LongArraySet.fromSorted(filtered)
    }
  }
}

class EdgeAccessor[S, D](val edgeSet: EdgeSet[S, D]) extends Logging {
  protected var _destId: Long = -1L

  def seek(id: Id[D]): Boolean = seek(id.id)
  def seek(id: Long): Boolean = {
    _destId = id
    edgeSet.destIdLongSet.contains(id)
  }

  def destId: Long = _destId

  def createdAt: Long = throw new UnsupportedOperationException
  def isPublic: Boolean = true
  def isPrivate: Boolean = false
  def bookmarkId: Long = throw new UnsupportedOperationException

  def getCreatedAt(id: Long): Long = throw new UnsupportedOperationException
  def getBookmarkId(id: Long): Long = throw new UnsupportedOperationException
}

trait MaterializedEdgeSet[S,D] extends EdgeSet[S, D] {
  protected var cache: (Searcher, Array[Int]) = (null, null)

  protected def getDocIds(searcher: Searcher): Array[Int] = {
    cache match {
      case (curSearcher, curDocIds) if (curSearcher eq searcher) =>
        curDocIds
      case _ =>
        val mapper = searcher.indexReader.asAtomicReader.getIdMapper
        val docids = destIdSet.map{ id => mapper.getDocId(id.id) }.filter{ _ >= 0 }.toArray
        Sorting.quickSort(docids)
        cache = (searcher, docids)
        docids
    }
  }

  override def getDestDocIdSetIterator(searcher: Searcher): DocIdSetIterator = toDocIdSetIterator(getDocIds(searcher))
}

trait LuceneBackedEdgeSet[S, D] extends EdgeSet[S, D] {
  val searcher: Searcher

  private[this] lazy val lazyDestIdLongSet: Set[Long] = {
    val mapper = searcher.indexReader.asAtomicReader.getIdMapper
    getDestDocIdSetIterator(searcher).map(docid => mapper.getId(docid)).toSet
  }

  private[this] lazy val lazyDestIdSet: Set[Id[D]] = new IdSetWrapper[D](lazyDestIdLongSet)

  override def destIdLongSet = lazyDestIdLongSet
  override def destIdSet = lazyDestIdSet

  override def size = getDestDocIdSetIterator(searcher).size

  override def getDestDocIdSetIterator(searcher: Searcher): DocIdSetIterator = {
    val td = searcher.indexReader.asAtomicReader.termDocsEnum(createSourceTerm)
    if (td != null) td else emptyDocIdSetIterator
  }

  protected def createSourceTerm: Term
}

trait IdSetEdgeSet[S, D] extends MaterializedEdgeSet[S, D] {
  override lazy val destIdLongSet: Set[Long] = destIdSet.map(_.id)
  override def size = destIdSet.size
}

trait LongSetEdgeSet[S, D] extends MaterializedEdgeSet[S, D] {
  protected val longArraySet: LongArraySet

  override def destIdLongSet = longArraySet
  override lazy val destIdSet: Set[Id[D]] = destIdLongSet.map(Id[D](_))
  override def size = longArraySet.size

  override def accessor: EdgeAccessor[S, D] = new EdgeAccessor[S, D](this) {
    protected var index: Int = -1
    override def seek(id: Long) = {
      _destId = id
      index = longArraySet.findIndex(id)
      index >= 0
    }
  }
}

trait LongSetEdgeSetWithAttributes[S, D] extends LongSetEdgeSet[S, D] {
  protected def createdAtByIndex(idx: Int): Long
  protected def isPublicByIndex(idx: Int): Boolean
  protected def bookmarkIdByIndex(idx: Int): Long = throw new UnsupportedOperationException

  override def accessor: EdgeAccessor[S, D] = new EdgeAccessor[S, D](this) {
    protected var index: Int = -1
    override def seek(id: Long) = {
      _destId = id
      index = longArraySet.findIndex(id)
      index >= 0
    }
    override def createdAt: Long = if (index >= 0) createdAtByIndex(index) else throw new IllegalStateException("accessor is not positioned")
    override def isPublic: Boolean = if (index >= 0) isPublicByIndex(index) else throw new IllegalStateException("accessor is not positioned")
    override def bookmarkId: Long = if (index >= 0) bookmarkIdByIndex(index) else throw new IllegalStateException("accessor is not positioned")

    override def getCreatedAt(id: Long): Long = {
      val idx = longArraySet.findIndex(id)
      if (idx >= 0) {
        createdAtByIndex(idx)
      } else {
        log.error(s"failed in getCreatedAt: src=${sourceId} dest=${id} idx=${idx}")
        if (longArraySet.verify) {
          if (longArraySet.iterator.forall(_ != id)) log.error(s"verified the data structure, but the key does not exists")
        }
        0L //throw new NoSuchElementException(s"failed to find id: ${id}")
      }
    }
    override def getBookmarkId(id: Long): Long = {
      val idx = longArraySet.findIndex(id)
      if (idx >= 0) {
        bookmarkIdByIndex(idx)
      } else {
        log.error(s"failed in getBookmarkId: src=${sourceId} dest=${id} idx=${idx}")
        if (longArraySet.verify) {
          if (longArraySet.iterator.forall(_ != id)) log.error(s"verified the data structure, but the key does not exists")
        }
        -1L //throw new NoSuchElementException(s"failed to find id: ${id}")
      }
    }
  }

  override def filterByTimeRange(start: Long, end: Long): EdgeSet[S, D] = {
    val buf = new ArrayBuffer[Long]
    var size = longArraySet.size
    var i = 0
    while (i < size) {
      val timestamp = createdAtByIndex(i)
      if (start <= timestamp && timestamp <= end) buf += longArraySet.key(i)
      i += 1
    }
    val filtered = buf.toArray
    Sorting.quickSort(filtered)
    val inheritedSourceId = sourceId
    new LongSetEdgeSet[S, D] {
      override val sourceId: Id[S] = inheritedSourceId
      override val longArraySet = LongArraySet.fromSorted(filtered)
    }
  }
}

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

class IdSetWrapper[T](inner: Set[Long]) extends Set[Id[T]] {

  override def contains(elem: Id[T]): Boolean = inner.contains(elem.id)

  override def iterator: Iterator[Id[T]] = inner.iterator.map{ Id[T](_) }

  override def +(elem: Id[T]): Set[Id[T]] = new IdSetWrapper[T](inner + elem.id)

  override def -(elem: Id[T]): Set[Id[T]] = new IdSetWrapper[T](inner - elem.id)

  override def size: Int = inner.size
}

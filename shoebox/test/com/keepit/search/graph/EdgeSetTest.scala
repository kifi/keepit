package com.keepit.search.graph

import com.keepit.common.db._
import com.keepit.test._
import com.keepit.inject._
import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import com.keepit.search.util.LongArraySet

class EdgeSetTest extends Specification {

  class Vertex

  class TestEdgeSet(
    override val sourceId: Id[Vertex],
    override protected val longArraySet: LongArraySet,
    timeArray: Array[Long]
  ) extends LongSetEdgeSetWithCreatedAt[Vertex, Vertex] {
    override protected def createdAtByIndex(idx:Int): Long = timeArray(idx)
    override protected def isPublicByIndex(idx: Int): Boolean = true
  }

  "EdgeSet" should {
    "filter edges by time range" in {
      // sorted ids
      val idArray = Array[Long](1, 2, 3, 4, 5, 6, 7, 8, 9)
      val timeArray = Array[Long](100, 200, 300, 110, 120, 130, 101, 102, 103)

      val edgeSet = new TestEdgeSet(Id[Vertex](0), LongArraySet.fromSorted(idArray), timeArray)

      edgeSet.filterByTimeRange(100, 130).destIdLongSet === Set[Long](1, 4, 5, 6, 7, 8, 9)
      edgeSet.filterByTimeRange(101, 119).destIdLongSet === Set[Long](4, 7, 8, 9)

      // unsorted ids (reversed arrays)
      val idArrayR = idArray.reverse
      val timeArrayR = timeArray.reverse

      val edgeSetR = new TestEdgeSet(Id[Vertex](0), LongArraySet.from(idArrayR), timeArray)

      edgeSet.filterByTimeRange(100, 130).destIdLongSet === Set[Long](1, 4, 5, 6, 7, 8, 9)
      edgeSet.filterByTimeRange(101, 119).destIdLongSet === Set[Long](4, 7, 8, 9)
    }
  }
}

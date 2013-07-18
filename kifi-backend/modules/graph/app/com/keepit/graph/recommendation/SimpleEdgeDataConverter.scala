package com.keepit.graph.recommendation

import edu.cmu.graphchi.datablocks.BytesToValueConverter
import com.keepit.graph.model._
import com.keepit.common.db._
import com.keepit.model._

object SimpleEdgeDataConverter extends BytesToValueConverter[EdgeData] {

  def sizeOf() = 2

  def setValue(array: Array[Byte], data: EdgeData) = {

    val tag = data match {
      case _: KeptData => EdgeData.oneByteRepresentation[KeptData]()
      case _: FollowsData => EdgeData.oneByteRepresentation[FollowsData]()
      case _: CollectsData => EdgeData.oneByteRepresentation[CollectsData]()
      case _: ContainsData => EdgeData.oneByteRepresentation[ContainsData]()
    }

    val state = data.state match {
      case State("inactive") => 0
      case _ => 1
    }

    array(0) = state.toByte
    array(1) = tag.toByte
  }

  def getValue(array: Array[Byte]) = {
    val stateString = array(0) match {
      case 0 => "inactive"
      case 1 => "active"
    }

    array(1) match {
      case 0 => KeptData(State[Bookmark](stateString))
      case 1 => FollowsData(State[UserConnection](stateString))
      case 2 => CollectsData(State[Collection](stateString))
      case 3 => ContainsData(State[KeepToCollection](stateString))
    }
  }
}

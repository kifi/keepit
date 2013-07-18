package com.keepit.graph.recommendation

import edu.cmu.graphchi.datablocks.BytesToValueConverter
import com.keepit.graph.model._
import com.keepit.common.db._
import com.keepit.model._

object SimpleVertexDataConverter extends BytesToValueConverter[VertexData] {
  def sizeOf() = 2

  def setValue(array: Array[Byte], data: VertexData) = {

    val tag = data match {
      case _: UserData => VertexData.fourBitRepresentation[UserData]()
      case _: UriData => VertexData.fourBitRepresentation[UriData]()
      case _: CollectionData => VertexData.fourBitRepresentation[CollectionData]()
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
      case 0 => UserData(State[User](stateString))
      case 1 => UriData(State[NormalizedURI](stateString))
      case 2 => CollectionData(State[Collection](stateString))
    }
  }
}

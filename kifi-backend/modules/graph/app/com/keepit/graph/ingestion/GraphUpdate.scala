package com.keepit.graph.ingestion

import com.keepit.graph.model._
import com.keepit.common.db.SequenceNumber
import com.keepit.common.reflection.CompanionTypeSystem

trait GraphUpdate { self =>
  type U >: self.type <: GraphUpdate
  def ingest()(implicit writer: GraphWriter): Unit
  def seq: SequenceNumber[U]
  def kind: GraphUpdateKind[U]
}

trait GraphUpdateKind[U <: GraphUpdate] {
  def code: String
  def seq(value: Long): SequenceNumber[U] = SequenceNumber(value)
}

object GraphUpdateKind {
  val all: Set[GraphUpdateKind[_ <: GraphUpdate]] = CompanionTypeSystem[GraphUpdate, GraphUpdateKind[_ <: GraphUpdate]]("I")
  private val byCode: Map[String, GraphUpdateKind[_ <: GraphUpdate]] = {
    require(all.size == all.map(_.code).size, "Duplicate GraphUpdateKind names.")
    all.map { ingestableKind => ingestableKind.code -> ingestableKind }.toMap
  }
  def apply(code: String): GraphUpdateKind[_ <: GraphUpdate] = byCode(code)
}

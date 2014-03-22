package com.keepit.cortex.core

trait FeatureRepresentation[T, M <: StatModel] {
  def vectorize: Array[Float]
}

trait FeatureRepresenter[T, M <: StatModel]{
  val version: ModelVersion[M]
  def apply(datum: T): Option[FeatureRepresentation[T, M]]
}

package com.keepit.cortex.core

trait FeatureRepresentation[T, M <: StatModel] extends Versionable[M] {
  def vectorize: Array[Float]
}

case class FloatVecFeature[T, M <: StatModel](value: Array[Float]) extends FeatureRepresentation[T, M] {
  override def vectorize: Array[Float] = value
}

trait FeatureRepresenter[T, M <: StatModel, +FT <: FeatureRepresentation[T, M]] {
  val version: ModelVersion[M]
  val dimension: Int
  def apply(datum: T): Option[FT]
  def getRawVector(datum: T): Option[Array[Float]] = apply(datum).map { _.vectorize } // subclass may override this to improve performance
}

trait BinaryFeatureFormatter[T <: FeatureRepresentation[_, _ <: StatModel]] {
  def toBinary(m: T): Array[Byte]
  def fromBinary(bytes: Array[Byte]): T
}

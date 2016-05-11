package com.keepit.common.util

object MapHelpers {
  def fromPairs[K, V](xs: Seq[(K, V)]): Map[K, Seq[V]] = xs.groupBy(_._1).mapValues(_.map(_._2))
  def unionWithKey[K, V](op: (K, (V, V)) => V)(xm: Map[K, V], ym: Map[K, V]): Map[K, V] = {
    // NB: I did a few tests and this is by far the fastest "API-only" (aka. without access to the underlying data structure)
    // way to merge two maps that I've found
    ym ++ xm.map {
      case (k, vx) => k -> ym.get(k).fold(vx)(vy => op(k, (vx, vy)))
    }
  }
  def unionWith[K, V](op: (V, V) => V)(xm: Map[K, V], ym: Map[K, V]): Map[K, V] = unionWithKey[K, V] { case (_, (v1, v2)) => op(v1, v2) }(xm, ym)

  // Technically, if `op` is not a commutative operator it is wrong to use `fold` here, since it does not guarantee an order
  // of operations (unlike foldLeft or foldRight). `fold` assumes a commutative operation. Use at your own risk.
  def unionsWith[K, V](op: (V, V) => V)(maps: Traversable[Map[K, V]]): Map[K, V] = {
    maps.fold(Map.empty)(unionWith(op))
  }

  // left-biased union
  def union[K, V](xm: Map[K, V], ym: Map[K, V]): Map[K, V] = ym ++ xm
  // Since `union` is equivalent to `unionWith(_._1)` and `_._1` is certainly not commutative,
  // this explicitly has undefined behavior. Use at your own risk
  def unions[K, V](maps: Traversable[Map[K, V]]): Map[K, V] = maps.fold(Map.empty)(union)
}

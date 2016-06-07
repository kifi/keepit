package com.keepit.shoebox.path

import com.keepit.common.path.Path

object PathHash {
  def apply(path: Path): Int = path.relativeWithLeadingSlash.hashCode
}

package com.keepit.normalizer

import com.keepit.model.NormalizedURI

sealed trait Action
case object ACCEPT extends Action
case object REJECT extends Action
case class Check(contentCheck: ContentCheck) extends Action

object Action {
  def apply(currentReference: NormalizedURI, contentChecks: Seq[ContentCheck])(candidate: NormalizationCandidate): Action = candidate match {
    case _: TrustedCandidate => ACCEPT
    case _: UntrustedCandidate => contentChecks.find(_.isDefinedAt(candidate)).map(Check).getOrElse(REJECT)
  }
}
